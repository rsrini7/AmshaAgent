package com.arcclaw.core.persistence;

import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.scope.WorkspaceScopeHolder;

/**
 * Asynchronous persistence engine using a single background Virtual Thread
 * to drain a {@link LinkedBlockingQueue} of {@link PersistenceEvent}s.
 *
 * <p><strong>Two tiers:</strong>
 * <ul>
 *   <li>{@code CRITICAL} — caller blocks on a {@code CompletableFuture} until
 *       the write is confirmed. If all retries fail a
 *       {@link ComplianceViolationException} is propagated to the caller.</li>
 *   <li>{@code TELEMETRY} — fire-and-forget. On retry exhaustion the event is
 *       silently discarded with a WARN log.</li>
 * </ul>
 *
 * <p><strong>Context propagation rule:</strong> each event carries its own
 * {@code sessionId}. The consumer loop calls
 * {@link WorkspaceScopeHolder#run(String, Runnable)} per-event, never
 * wrapping the entire consumer thread.
 *
 * <p><strong>Transactional boundary:</strong> every DB write uses
 * {@code QuarkusTransaction.requiringNew().run(...)} because the background
 * Virtual Thread is not a CDI-managed bean call chain.
 */
@ApplicationScoped
public class AsyncPersistenceEngine {

    private static final Logger log = Logger.getLogger(AsyncPersistenceEngine.class);

    private static final int MAX_RETRIES = 5;
    private static final long BACKOFF_BASE_MS = 50;

    private static final int QUEUE_CAPACITY = 10000;
    private final LinkedBlockingQueue<PersistenceEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    @Inject
    EntityManager em;

    @Inject
    ManagedExecutor managedExecutor;

    private volatile boolean running = true;

    @PostConstruct
    void startConsumer() {
        // Capture the current Thread Context Class Loader to propagate it to the background thread.
        // This is necessary for ServiceLoader (used by Quarkus Config) to work correctly
        // in an unmanaged virtual thread.
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        Thread.ofVirtual()
              .inheritInheritableThreadLocals(false)
              .name("arcclaw-persistence-consumer")
              .start(() -> {
                  Thread.currentThread().setContextClassLoader(tccl);
                  consumerLoop();
              });
        log.info("[AsyncPersistenceEngine] Background consumer started on VirtualThread with TCCL propagation.");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        running = false;
    }

    /**
     * Submit a CRITICAL event. The calling thread BLOCKS until the write
     * is confirmed or a {@link ComplianceViolationException} is thrown.
     */
    public void submitCritical(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.CRITICAL) {
            throw new IllegalArgumentException("submitCritical requires a CRITICAL event");
        }
        
        try {
            // put() blocks the caller if the queue is full, providing natural backpressure
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
        }

        // Block the caller until the consumer confirms or fails the future
        try {
            event.getFuture().join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof ComplianceViolationException cve) {
                throw cve;
            }
            throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), cause);
        }
    }

    /**
     * Submit a TELEMETRY event. The calling thread does NOT block.
     */
    public void submitTelemetry(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.TELEMETRY) {
            throw new IllegalArgumentException("submitTelemetry requires a TELEMETRY event");
        }
        // offer() returns false if the queue is full. We drop telemetry under pressure.
        if (!queue.offer(event)) {
            log.warnf("[AsyncPersistenceEngine] Telemetry queue full (%d items) — dropping event %s for session %s",
                    QUEUE_CAPACITY, event.getEventType(), event.getSessionId());
        }
    }

    private final AtomicBoolean processing = new AtomicBoolean(false);

    /**
     * Blocks until all currently queued events are processed.
     * Use only in tests!
     */
    public void waitForIdle() {
        // Wait a tiny bit for any work that hasn't hit the queue yet
        sleepQuietly(10);
        while (!queue.isEmpty() || processing.get()) {
            sleepQuietly(10);
        }
    }

    // -------------------------------------------------------------------------
    // Consumer loop — runs on a single daemon VirtualThread
    // -------------------------------------------------------------------------

    private void consumerLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Poll with timeout to check for shutdown
                PersistenceEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    processing.set(true);
                    try {
                        processEvent(event);
                    } finally {
                        processing.set(false);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[AsyncPersistenceEngine] Consumer interrupted — shutting down.");
            } catch (Exception e) {
                if (running) {
                    log.errorf(e, "[AsyncPersistenceEngine] Unexpected error in consumer loop");
                }
            }
        }
    }

    private void processEvent(PersistenceEvent event) {
        // Guard against processing during shutdown when Arc is gone
        if (Arc.container() == null) {
            log.warnf("[AsyncPersistenceEngine] Arc container is gone. Discarding event %s for session %s", 
                    event.getEventType(), event.getSessionId());
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(new RuntimeException("Arc container is null"));
            }
            return;
        }

        // CRITICAL: bind per-event scope so WorkspaceScopeHolder.getRequired() works
        // inside the JPA write. NEVER wrap the whole consumer thread — it processes
        // events from many different sessions.
        try {
            WorkspaceScopeHolder.run(event.getSessionId(), () -> {
                executeWithRetry(event);
            });
        } catch (ComplianceViolationException cve) {
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(cve);
            }
        } catch (Exception e) {
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(
                    new ComplianceViolationException(event.getSessionId(), event.getEventType(), e));
            }
        }
    }

    /**
     * Attempts the DB write up to {@value #MAX_RETRIES} times.
     * Uses linear back-off: {@code BACKOFF_BASE_MS × attempt}.
     *
     * <p>SQLite BUSY detection: relies on inspecting the exception message
     * AND the full {@code getCause()} chain rather than a single string check,
     * because the Hibernate SQLite dialect may wrap the error several layers deep.
     */
    private void executeWithRetry(PersistenceEvent event) {
        // Second-check: container may have gone since processEvent() passed its guard
        if (!running || Arc.container() == null) {
            log.debugf("[AsyncPersistenceEngine] Skipping write (shutdown): %s session=%s",
                    event.getEventType(), event.getSessionId());
            if (event.getFuture() != null) event.getFuture().complete(null);
            return;
        }
        
        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                // CRITICAL: every write must create its own transaction —
                // the VirtualThread consumer is NOT a CDI-managed proxy.
                QuarkusTransaction.requiringNew().run(() ->
                    event.getDbAction().accept(em)
                );

                // Write succeeded — unblock CRITICAL callers
                if (event.getFuture() != null) {
                    event.getFuture().complete(null);
                }
                return;

            } catch (Exception e) {
                lastException = e;
                if (isSqliteBusy(e)) {
                    long backoff = BACKOFF_BASE_MS * attempt;
                    log.warnf("[AsyncPersistenceEngine] SQLite BUSY on attempt %d/%d for session=%s — retrying in %dms. Error: %s",
                            attempt, MAX_RETRIES, event.getSessionId(), backoff, e.getMessage());
                    sleepQuietly(backoff);
                } else {
                    // Non-BUSY error — check if it's a shutdown race before logging as ERROR
                    if (!running || Arc.container() == null) {
                        log.debugf("[AsyncPersistenceEngine] Shutdown race: discarding event %s for session=%s",
                                event.getEventType(), event.getSessionId());
                        if (event.getFuture() != null) {
                            event.getFuture().complete(null); // unblock any waiting callers cleanly
                        }
                        return;  // do NOT call handleExhausted — this is expected during teardown
                    }
                    // Genuine non-retryable error during normal operation
                    log.errorf(e, "[AsyncPersistenceEngine] Non-retryable error for session=%s eventType=%s. Class: %s",
                            event.getSessionId(), event.getEventType(), e.getClass().getName());
                    break;
                }
            }
        }

        // All retries exhausted (or non-retryable error)
        handleExhausted(event, lastException);
    }

    private void handleExhausted(PersistenceEvent event, Exception lastException) {
        if (event.getTier() == PersistenceEvent.Tier.CRITICAL) {
            ComplianceViolationException cve =
                new ComplianceViolationException(event.getSessionId(), event.getEventType(), lastException);
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(cve);
            }
            throw cve;
        } else {
            // TELEMETRY — discard silently with WARN
            log.warnf("[AsyncPersistenceEngine] TELEMETRY event discarded after %d retries " +
                      "(session=%s, eventType=%s): %s",
                    MAX_RETRIES, event.getSessionId(), event.getEventType(),
                    lastException != null ? lastException.getMessage() : "unknown");
        }
    }

    /**
     * Checks whether the exception (or any cause in the chain) indicates an
     * SQLite BUSY / database-locked error.
     * Inspects the full getCause() chain rather than relying solely on
     * e.getMessage() to handle Hibernate wrapping several layers deep.
     */
    static boolean isSqliteBusy(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getSimpleName();
            if (className.equals("LockAcquisitionException") || 
                className.equals("OptimisticLockException") ||
                className.equals(SqliteErrorStrings.SQLITE_EXCEPTION)) {
                return true;
            }

            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains(SqliteErrorStrings.BUSY) || 
                    lower.contains(SqliteErrorStrings.DATABASE_LOCKED) || 
                    lower.contains(SqliteErrorStrings.SQLITE_BUSY) ||
                    lower.contains(SqliteErrorStrings.SNAPSHOT)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
