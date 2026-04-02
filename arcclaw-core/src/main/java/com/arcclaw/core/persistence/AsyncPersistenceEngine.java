package com.arcclaw.core.persistence;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * Asynchronous persistence engine using a single background Virtual Thread
 * to drain a {@link LinkedBlockingQueue} of {@link PersistenceEvent}s.
 *
 * <p>
 * <strong>Two tiers:</strong>
 * <ul>
 * <li>{@code CRITICAL} — caller blocks on a {@code CompletableFuture} until
 * the write is confirmed. If all retries fail a
 * {@link ComplianceViolationException} is propagated to the caller.</li>
 * <li>{@code TELEMETRY} — fire-and-forget. On retry exhaustion the event is
 * silently discarded with a WARN log.</li>
 * </ul>
 *
 * <p>
 * <strong>Context propagation rule:</strong> each event carries its own
 * {@code sessionId}. The consumer loop calls
 * {@link WorkspaceScopeHolder#run(String, Runnable)} per-event, never
 * wrapping the entire consumer thread.
 *
 * <p>
 * <strong>Transactional boundary:</strong> every DB write uses
 * {@code QuarkusTransaction.requiringNew().run(...)} because the background
 * Virtual Thread is not a CDI-managed bean call chain.
 */
@ApplicationScoped
public class AsyncPersistenceEngine {

    private static final Logger log = Logger.getLogger(AsyncPersistenceEngine.class);

    private static final int MAX_RETRIES = 5;
    private static final long BACKOFF_BASE_MS = 50;

    private static final int CRITICAL_QUEUE_CAPACITY = 500;
    private static final int TELEMETRY_QUEUE_CAPACITY = 5000;

    private final LinkedBlockingQueue<PersistenceEvent> criticalQueue = new LinkedBlockingQueue<>(
            CRITICAL_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<PersistenceEvent> telemetryQueue = new LinkedBlockingQueue<>(
            TELEMETRY_QUEUE_CAPACITY);

    @Inject
    MeterRegistry registry;

    @Inject
    AsyncPersistenceEngine self;

    @Inject
    EntityManager em;

    @Inject
    ManagedExecutor managedExecutor;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread criticalDrainThread;
    private Thread telemetryDrainThread;

    @PostConstruct
    void startDrainThreads() {
        // Capture the current Thread Context Class Loader to propagate it to the
        // background thread.
        // This is necessary for ServiceLoader (used by Quarkus Config) to work
        // correctly
        // in an unmanaged virtual thread.
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        criticalDrainThread = Thread.ofVirtual()
                .inheritInheritableThreadLocals(false)
                .name("arcclaw-audit-critical-drain")
                .unstarted(() -> {
                    Thread.currentThread().setContextClassLoader(tccl);
                    drainCritical();
                });

        telemetryDrainThread = Thread.ofVirtual()
                .inheritInheritableThreadLocals(false)
                .name("arcclaw-audit-telemetry-drain")
                .unstarted(() -> {
                    Thread.currentThread().setContextClassLoader(tccl);
                    drainTelemetry();
                });

        criticalDrainThread.start();
        telemetryDrainThread.start();
        log.info("[AsyncPersistenceEngine] Background drain threads started on VirtualThreads.");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        running.set(false);
        log.debug("[AsyncPersistenceEngine] Shutdown requested. Signaling drain threads...");

        if (criticalDrainThread != null)
            criticalDrainThread.interrupt();
        if (telemetryDrainThread != null)
            telemetryDrainThread.interrupt();

        try {
            // Give threads a short window to exit gracefully before Arc is gone
            if (criticalDrainThread != null)
                criticalDrainThread.join(Duration.ofMillis(200));
            if (telemetryDrainThread != null)
                telemetryDrainThread.join(Duration.ofMillis(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("[AsyncPersistenceEngine] Shutdown complete.");
    }

    /**
     * Submit a CRITICAL event. The calling thread BLOCKS until the write
     * is confirmed or a {@link ComplianceViolationException} is thrown.
     */
    public void submitCritical(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.CRITICAL) {
            throw new IllegalArgumentException("submitCritical requires a CRITICAL event");
        }

        if (!criticalQueue.offer(event)) {
            log.warn("Critical audit queue at capacity — persisting synchronously to avoid data loss");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "critical").increment();
            try {
                processEvent(event);
            } catch (Exception e) {
                throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
            }
            return;
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
     * Issue 7: Wait for persist of AgentCheckpoint using async event to avoid stale states.
     */
    public CompletableFuture<Void> persistAndWait(AgentCheckpoint checkpoint) {
        PersistenceEvent event = PersistenceEvent.critical(
            checkpoint.getSessionId(),
            SecurityEventType.CHECKPOINT_CREATED,
            (em) -> em.merge(checkpoint)
        );
        submitCritical(event);
        return event.getFuture();
    }

    /**
     * Submit a TELEMETRY event. The calling thread does NOT block.
     */
    public void submitTelemetry(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.TELEMETRY) {
            throw new IllegalArgumentException("submitTelemetry requires a TELEMETRY event");
        }
        // offer() returns false if the queue is full. We drop telemetry under pressure.
        if (!telemetryQueue.offer(event)) {
            log.debug("Telemetry audit queue at capacity — dropping event (acceptable)");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "telemetry").increment();
        }
    }

    private final AtomicBoolean processingCritical = new AtomicBoolean(false);
    private final AtomicBoolean processingTelemetry = new AtomicBoolean(false);

    /**
     * Blocks until all currently queued events are processed.
     * Use only in tests!
     */
    public void waitForIdle() {
        // Wait a tiny bit for any work that hasn't hit the queue yet
        sleepQuietly(10);
        while (!criticalQueue.isEmpty() || !telemetryQueue.isEmpty() || processingCritical.get()
                || processingTelemetry.get()) {
            sleepQuietly(10);
        }
    }

    /** For testing only */
    void clearForTest() {
        criticalQueue.clear();
        telemetryQueue.clear();
    }

    // -------------------------------------------------------------------------
    // Drain loops — run on Virtual Threads
    // -------------------------------------------------------------------------

    private void drainCritical() {
        PersistenceEvent event = null;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Use poll with timeout so we can re-check running flag regularly
                event = criticalQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }

                processingCritical.set(true);
                try {
                    processEvent(event); // may throw ComplianceViolationException
                } catch (ComplianceViolationException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IllegalStateException
                            && cause.getMessage() != null
                            && cause.getMessage().contains("HMAC key unavailable")) {
                        registry.counter("arcclaw.audit.drain.hmac_failure").increment();
                    }
                    log.errorf(
                            "Critical audit event lost — check persistence health. Event type: %s, Error: %s",
                            event.getEventType(), e.getMessage());
                    log.debugf(e, "Full stacktrace for critical audit failure");
                    if (event.getFuture() != null && !event.getFuture().isDone()) {
                        event.getFuture().completeExceptionally(e);
                    }
                } finally {
                    processingCritical.set(false);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[AsyncPersistenceEngine] Critical drain interrupted — shutting down.");
            } catch (Exception e) {
                if (running.get() && Arc.container() != null) {
                    log.errorf("[AsyncPersistenceEngine] Unexpected error in critical drain: %s", e.getMessage());
                    log.debugf(e, "Full stacktrace for unexpected critical drain error");
                }
                if (event != null && event.getFuture() != null && !event.getFuture().isDone()) {
                    event.getFuture().completeExceptionally(e);
                }
            }
        }
    }


    private void drainTelemetry() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Poll with timeout to check for shutdown
                PersistenceEvent event = telemetryQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    processingTelemetry.set(true);
                    try {
                        processEvent(event);
                    } finally {
                        processingTelemetry.set(false);
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[AsyncPersistenceEngine] Telemetry drain interrupted — shutting down.");
            } catch (Exception e) {
                if (running.get() && Arc.container() != null) {
                    log.errorf("[AsyncPersistenceEngine] Unexpected error in telemetry drain: %s", e.getMessage());
                    log.debugf(e, "[AsyncPersistenceEngine] Full stacktrace for telemetry drain error");
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
            throw cve;
        } catch (Exception e) {
            ComplianceViolationException cve = new ComplianceViolationException(event.getSessionId(),
                    event.getEventType(), e);
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(cve);
            }
            throw cve;
        }
    }

    /**
     * Attempts the DB write up to {@value #MAX_RETRIES} times.
     * Uses linear back-off: {@code BACKOFF_BASE_MS × attempt}.
     *
     * <p>
     * SQLite BUSY detection: relies on inspecting the exception message
     * AND the full {@code getCause()} chain rather than a single string check,
     * because the Hibernate SQLite dialect may wrap the error several layers deep.
     */
    private void executeWithRetry(PersistenceEvent event) {
        // Second-check: container may have gone since processEvent() passed its guard
        if (!running.get() || Arc.container() == null) {
            log.debugf("[AsyncPersistenceEngine] Skipping write (shutdown): %s session=%s",
                    event.getEventType(), event.getSessionId());
            if (event.getFuture() != null)
                event.getFuture().complete(null);
            return;
        }

        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                // CRITICAL: every write must create its own transaction
                // Architecture Nuance #29: EntityManager proxy must bind in @Transactional
                // method
                self.persistSynchronously(event);

                // Write succeeded — unblock CRITICAL callers
                if (event.getFuture() != null) {
                    event.getFuture().complete(null);
                }
                return;

            } catch (Exception e) {
                lastException = e;
                if (isSqliteBusy(e)) {
                    long backoff = BACKOFF_BASE_MS * attempt;
                    log.warnf(
                            "[AsyncPersistenceEngine] SQLite BUSY on attempt %d/%d for session=%s — retrying in %dms. Error: %s",
                            attempt, MAX_RETRIES, event.getSessionId(), backoff, e.getMessage());
                    sleepQuietly(backoff);
                } else {
                    // Non-BUSY error — check if it's a shutdown race before logging as ERROR
                    if (!running.get() || Arc.container() == null) {
                        log.debugf("[AsyncPersistenceEngine] Shutdown race: discarding event %s for session=%s",
                                event.getEventType(), event.getSessionId());
                        if (event.getFuture() != null) {
                            event.getFuture().complete(null); // unblock any waiting callers cleanly
                        }
                        return; // do NOT call handleExhausted — this is expected during teardown
                    }
                    // Genuine non-retryable error during normal operation
                    log.errorf("[AsyncPersistenceEngine] Non-retryable error for session=%s eventType=%s: %s",
                            event.getSessionId(), event.getEventType(), e.getMessage());
                    log.debugf(e, "[AsyncPersistenceEngine] Full stacktrace for non-retryable error for session=%s",
                            event.getSessionId());
                    break;
                }
            }
        }

        // All retries exhausted (or non-retryable error)
        if (lastException != null) {
            handleExhausted(event, lastException);
        } else {
            // Should not happen if loop logic is correct, but safety net
            if (event.getFuture() != null && !event.getFuture().isDone()) {
                event.getFuture().complete(null);
            }
        }
    }

    @Transactional(TxType.REQUIRES_NEW)
    public void persistSynchronously(PersistenceEvent event) {
        int hmacRetry = 0;
        while (true) {
            try {
                event.getDbAction().accept(em); // HMAC lookup happens here — before any SQLite write
                return;
            } catch (IllegalStateException e) {
                if (e.getMessage() != null
                        && e.getMessage().contains("HMAC key unavailable")
                        && hmacRetry < 3) {
                    hmacRetry++;
                    log.warnf("Audit HMAC key not yet available (boot-window race), " +
                            "retry %d/3 in 100ms — no SQLite lock held during sleep", hmacRetry);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    // Real failure or retries exhausted — propagate to drainCritical()
                    throw e;
                }
            }
        }
    }

    private void handleExhausted(PersistenceEvent event, Exception lastException) {
        if (event.getTier() == PersistenceEvent.Tier.CRITICAL) {
            ComplianceViolationException cve = new ComplianceViolationException(event.getSessionId(),
                    event.getEventType(), lastException);
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

            // Check for known "busy" exception types first
            if (className.equals(SqliteErrorStrings.LOCK_ACQUISITION_EXCEPTION) ||
                    className.equals(SqliteErrorStrings.OPTIMISTIC_LOCK_EXCEPTION)) {
                return true;
            }

            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();

                // If it's a constraint violation, it is NEVER a "busy" retryable error.
                // We must exit immediately to avoid infinite retry loops on bad data.
                if (lower.contains("constraint") || lower.contains("not null")) {
                    return false;
                }

                if (lower.contains(SqliteErrorStrings.BUSY) ||
                        lower.contains(SqliteErrorStrings.DATABASE_LOCKED) ||
                        lower.contains(SqliteErrorStrings.SQLITE_BUSY) ||
                        lower.contains(SqliteErrorStrings.SNAPSHOT)) {
                    return true;
                }
            }

            // For generic SQLiteException, we MUST check the message for "busy"
            // specifically, because SQLiteException is also used for NOT NULL and other
            // HARD errors.
            if (className.equals(SqliteErrorStrings.SQLITE_EXCEPTION)) {
                return msg != null && msg.toLowerCase().contains(SqliteErrorStrings.BUSY);
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
