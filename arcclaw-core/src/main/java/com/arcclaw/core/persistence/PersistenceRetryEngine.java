package com.arcclaw.core.persistence;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.core.audit.ComplianceViolationException;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles the SQLite-BUSY retry loop, HMAC boot-window retries, and retry-
 * exhaustion handling for {@link AsyncPersistenceEngine}.
 *
 * <p>Extracted from {@code AsyncPersistenceEngine} so that class can stay
 * focused on queue / drain-thread concerns only.
 *
 * <h2>Calling chain</h2>
 * <pre>
 * AsyncPersistenceEngine.processEvent(event)
 *   └─ WorkspaceScopeHolder.run(sessionId, () -> retryEngine.executeWithRetry(event))
 *        └─ PersistenceRetryEngine.executeWithRetry(event)          [retry loop]
 *             └─ self.persistSynchronously(event)                   [@Transactional REQUIRES_NEW]
 *                  └─ event.getDbAction().accept(em)                [actual JPA write]
 * </pre>
 *
 * <h2>isSqliteBusy vs SqliteErrorStrings.isBusy</h2>
 * {@link #isSqliteBusy(Throwable)} adds a <em>constraint-violation guard</em>
 * that {@link SqliteErrorStrings#isBusy(Throwable)} intentionally omits: if any
 * message in the cause chain contains {@code "constraint"} or {@code "not null"},
 * the method returns {@code false} immediately to prevent infinite retry loops on
 * hard data errors (e.g. NOT NULL, UNIQUE violations).
 */
@ApplicationScoped
public class PersistenceRetryEngine {

    private static final Logger log = Logger.getLogger(PersistenceRetryEngine.class);

    static final int    MAX_RETRIES     = 5;
    static final long   BACKOFF_BASE_MS = 50L;

    /** CDI self-reference so {@code @Transactional(REQUIRES_NEW)} is intercepted. */
    @Inject
    PersistenceRetryEngine self;

    /** Required inside {@link #persistSynchronously} for the {@code em.merge / em.persist} lambdas. */
    @Inject
    EntityManager em;

    // -------------------------------------------------------------------------
    // Public entry-point
    // -------------------------------------------------------------------------

    /**
     * Attempts the DB write up to {@value #MAX_RETRIES} times with exponential
     * back-off + jitter on SQLite BUSY.
     *
     * <p><strong>Pre-condition:</strong> the workspace scope for the event's
     * {@code sessionId} must already be bound ({@code WorkspaceScopeHolder.run(...)})
     * before calling this method, so that any {@code @WorkspaceScoped} beans
     * accessed inside the {@code dbAction} lambda resolve correctly.
     *
     * @throws ComplianceViolationException if the event is CRITICAL and all
     *         retries are exhausted, or if a non-retryable error occurs.
     */
    public void executeWithRetry(PersistenceEvent event) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                // Architecture Nuance #29: @Transactional proxy must be invoked via self.
                self.persistSynchronously(event);

                // Write succeeded — unblock CRITICAL callers.
                if (event.getFuture() != null) {
                    event.getFuture().complete(null);
                }
                return;

            } catch (Exception e) {
                lastException = e;

                if (isSqliteBusy(e)) {
                    long backoff = BACKOFF_BASE_MS * attempt
                            + ThreadLocalRandom.current().nextLong(0, 50);

                    String compactTrace = ExceptionUtils.getThrowableList(e).stream()
                            .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                            .collect(Collectors.joining(" → "));

                    log.warnf("AsyncPersistenceEngine SQLite BUSY on attempt %d/%d for session=%s "
                            + "retrying in %dms. Chain: %s",
                            attempt, MAX_RETRIES, event.getSessionId(), backoff, compactTrace);

                    sleepQuietly(backoff);

                } else {
                    // Non-BUSY: log as ERROR and stop retrying immediately.
                    log.errorf("AsyncPersistenceEngine Non-retryable error for session=%s eventType=%s: %s",
                            event.getSessionId(), event.getEventType(), e.getMessage());
                    log.debugf(e, "AsyncPersistenceEngine Full stacktrace for non-retryable error session=%s",
                            event.getSessionId());
                    break;
                }
            }
        }

        // All retries exhausted or non-retryable error reached.
        if (lastException != null) {
            handleExhausted(event, lastException);
        } else {
            // Safety-net: loop exited cleanly without a result (should never happen).
            if (event.getFuture() != null && !event.getFuture().isDone()) {
                event.getFuture().complete(null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Transactional write  (@Transactional must be on a public method of a CDI bean)
    // -------------------------------------------------------------------------

    /**
     * Executes the event's {@code dbAction} inside a {@code REQUIRES_NEW}
     * transaction.
     *
     * <p><strong>HMAC boot-window race:</strong> on startup, Vault keys may not
     * yet be available when the first audit events are flushed. The method retries
     * up to 3 times (sleeping 100 ms) before propagating the error to the drain
     * loop. No SQLite lock is held during the sleep.
     *
     * <p>Must be called via {@link #self} (the CDI proxy) for the
     * {@code @Transactional} interceptor to fire.
     */
    @Transactional(TxType.REQUIRES_NEW)
    public void persistSynchronously(PersistenceEvent event) {
        int hmacRetry = 0;
        while (true) {
            try {
                // Pass the injected EntityManager into the dbAction lambda.
                // Many lambdas use it directly (e.g. em -> em.merge(checkpoint));
                // repository-backed lambdas receive it but may ignore it.
                event.getDbAction().accept(em);
                return;

            } catch (IllegalStateException e) {
                if (e.getMessage() != null
                        && e.getMessage().contains("HMAC key unavailable")
                        && hmacRetry < 3) {
                    hmacRetry++;
                    log.warnf("Audit HMAC key not yet available (boot-window race), "
                            + "retry %d/3 in 100ms — no SQLite lock held during sleep.", hmacRetry);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    // Real failure or HMAC retries exhausted — propagate up.
                    throw e;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Exhaustion handler
    // -------------------------------------------------------------------------

    /**
     * Called when {@link #executeWithRetry} has exhausted all attempts.
     *
     * <ul>
     *   <li><strong>CRITICAL</strong> → wraps in {@link ComplianceViolationException},
     *       completes the future exceptionally, then re-throws (fail-closed).</li>
     *   <li><strong>TELEMETRY</strong> → logs a WARN and discards silently
     *       (fire-and-forget tier, acceptable data loss).</li>
     * </ul>
     */
    void handleExhausted(PersistenceEvent event, Exception lastException) {
        if (event.getTier() == PersistenceEvent.Tier.CRITICAL) {
            ComplianceViolationException cve = new ComplianceViolationException(
                    event.getSessionId(), event.getEventType(), lastException);
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(cve);
            }
            throw cve;
        } else {
            // TELEMETRY: discard silently with WARN — this is acceptable.
            log.warnf("AsyncPersistenceEngine TELEMETRY event discarded after %d retries "
                    + "session=%s, eventType=%s: %s",
                    MAX_RETRIES, event.getSessionId(), event.getEventType(),
                    lastException != null ? lastException.getMessage() : "unknown");
        }
    }

    // -------------------------------------------------------------------------
    // SQLite BUSY detection
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the exception or <em>any cause</em> in the chain
     * indicates an SQLite BUSY / database-locked condition.
     *
     * <p>Inspects the full {@link Throwable#getCause()} chain rather than relying
     * solely on {@code e.getMessage()}, because Hibernate may wrap the JDBC
     * {@code SQLiteException} several layers deep.
     *
     * <h3>Constraint-violation guard (differs from SqliteErrorStrings.isBusy)</h3>
     * If any message contains {@code "constraint"} or {@code "not null"}, returns
     * {@code false} immediately to prevent infinite retry loops on hard schema
     * errors (NOT NULL, UNIQUE, FK violations).
     */
    static boolean isSqliteBusy(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getSimpleName();

            // 1. Known lock-exception class names — always busy, no message needed.
            if (className.equals(SqliteErrorStrings.LOCK_ACQUISITION_EXCEPTION)
                    || className.equals(SqliteErrorStrings.OPTIMISTIC_LOCK_EXCEPTION)) {
                return true;
            }

            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();

                // 2. Constraint-violation guard — hard errors must NOT be retried.
                if (lower.contains("constraint") || lower.contains("not null")) {
                    return false;
                }

                // 3. Standard busy / locked message fragments.
                if (lower.contains(SqliteErrorStrings.BUSY)
                        || lower.contains(SqliteErrorStrings.DATABASE_LOCKED)
                        || lower.contains(SqliteErrorStrings.SQLITE_BUSY)
                        || lower.contains(SqliteErrorStrings.SNAPSHOT)) {
                    return true;
                }

                // 4. Generic SQLiteException — check message for "busy" specifically,
                //    because SQLiteException covers NOT NULL and other hard errors too.
                if (className.equals(SqliteErrorStrings.SQLITE_EXCEPTION)) {
                    return lower.contains(SqliteErrorStrings.BUSY);
                }
            }

            current = current.getCause();
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
