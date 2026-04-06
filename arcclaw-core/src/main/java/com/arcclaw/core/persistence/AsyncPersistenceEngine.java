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

import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.ShutdownEvent;
import org.jboss.logging.Logger;

/**
 * Asynchronous persistence engine using two background Virtual Threads to drain
 * a {@link LinkedBlockingQueue} of {@link PersistenceEvent}s.
 *
 * <h2>Two tiers</h2>
 * <ul>
 *   <li><strong>CRITICAL</strong> — caller blocks on a {@link CompletableFuture}
 *       until the write is confirmed. If all retries fail, a
 *       {@link ComplianceViolationException} is propagated to the caller.</li>
 *   <li><strong>TELEMETRY</strong> — fire-and-forget. On retry exhaustion the
 *       event is silently discarded with a WARN log.</li>
 * </ul>
 *
 * <h2>Context propagation rule</h2>
 * Each event carries its own {@code sessionId}. {@link #processEvent} calls
 * {@link WorkspaceScopeHolder#run(String, Runnable)} per-event, never wrapping
 * the entire consumer thread (which processes events from many different sessions).
 *
 * <h2>Transactional boundary</h2>
 * Every DB write is delegated to
 * {@link PersistenceRetryEngine#persistSynchronously(PersistenceEvent)}, which
 * uses {@code @Transactional(REQUIRES_NEW)}, because the background Virtual Thread
 * is not a CDI-managed bean call chain.
 *
 * <h2>SQLite BUSY retry</h2>
 * All retry logic and {@code isSqliteBusy} detection have been extracted to
 * {@link PersistenceRetryEngine} to keep this class focused on queue/drain concerns.
 */
@ApplicationScoped
public class AsyncPersistenceEngine {

    private static final Logger log = Logger.getLogger(AsyncPersistenceEngine.class);

    private static final int  CRITICAL_QUEUE_CAPACITY  = 500;
    private static final int  TELEMETRY_QUEUE_CAPACITY = 5_000;

    // -------------------------------------------------------------------------
    // Queues
    // -------------------------------------------------------------------------

    private final LinkedBlockingQueue<PersistenceEvent> criticalQueue  =
            new LinkedBlockingQueue<>(CRITICAL_QUEUE_CAPACITY);

    private final LinkedBlockingQueue<PersistenceEvent> telemetryQueue =
            new LinkedBlockingQueue<>(TELEMETRY_QUEUE_CAPACITY);

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Inject
    MeterRegistry registry;

    /** Handles retry loop, @Transactional boundary, exhaustion, and BUSY detection. */
    @Inject
    PersistenceRetryEngine retryEngine;

    // -------------------------------------------------------------------------
    // Lifecycle state
    // -------------------------------------------------------------------------

    private final AtomicBoolean running          = new AtomicBoolean(true);
    private final AtomicBoolean processingCritical  = new AtomicBoolean(false);
    private final AtomicBoolean processingTelemetry = new AtomicBoolean(false);

    private Thread criticalDrainThread;
    private Thread telemetryDrainThread;

    // -------------------------------------------------------------------------
    // Startup / Shutdown
    // -------------------------------------------------------------------------

    /**
     * Spawns two unmanaged Virtual Threads — one for each queue tier.
     *
     * <p>The current Thread's context class loader is captured and propagated to
     * both threads so that {@code ServiceLoader} (used by Quarkus Config) works
     * correctly outside a CDI-managed call chain.
     */
    @PostConstruct
    void startDrainThreads() {

        // Wait for Arc/CDI container to be fully ready before draining
        if (Arc.container() == null || !Arc.container().isRunning()) {
            log.warn("CDI container not ready — delaying drain thread start");
            return; // Quarkus will re-fire StartupEvent once ready
        }

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
        log.info("AsyncPersistenceEngine Background drain threads started on VirtualThreads.");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        running.set(false);
        log.debug("AsyncPersistenceEngine Shutdown requested. Signaling drain threads...");

        if (criticalDrainThread  != null) criticalDrainThread.interrupt();
        if (telemetryDrainThread != null) telemetryDrainThread.interrupt();

        try {
            // Give threads a short window to exit gracefully before Arc is gone.
            if (criticalDrainThread  != null) criticalDrainThread.join(Duration.ofMillis(200));
            if (telemetryDrainThread != null) telemetryDrainThread.join(Duration.ofMillis(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("AsyncPersistenceEngine Shutdown complete.");
    }

    // -------------------------------------------------------------------------
    // Public submission API
    // -------------------------------------------------------------------------

    /**
     * Submits a CRITICAL event. The calling thread <strong>blocks</strong> until
     * the write is confirmed or a {@link ComplianceViolationException} is thrown.
     *
     * <p>If the queue is full (back-pressure), the event is persisted synchronously
     * on the caller's thread to avoid data loss, and a counter is incremented.
     *
     * @throws ComplianceViolationException if the write fails after all retries.
     * @throws IllegalArgumentException     if the event tier is not CRITICAL.
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
            return; // Already completed by processEvent, no need to join future.
        }

        // Block the caller until the consumer confirms or fails the future.
        try {
            event.getFuture().join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof ComplianceViolationException cve) throw cve;
            throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), cause);
        }
    }

    /**
     * Submits a TELEMETRY event. The calling thread does <strong>NOT</strong> block.
     *
     * <p>If the queue is full, the event is dropped (acceptable for telemetry).
     *
     * @throws IllegalArgumentException if the event tier is not TELEMETRY.
     */
    public void submitTelemetry(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.TELEMETRY) {
            throw new IllegalArgumentException("submitTelemetry requires a TELEMETRY event");
        }

        if (!telemetryQueue.offer(event)) {
            log.debug("Telemetry audit queue at capacity — dropping event (acceptable)");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "telemetry").increment();
        }
    }

    /**
     * Persists an {@link AgentCheckpoint} via the CRITICAL path and returns a
     * {@link CompletableFuture} that completes when the write is confirmed.
     *
     * <p>Issue #7: uses the async event to avoid stale-state races between the
     * caller and the background drain thread.
     */
    public CompletableFuture<Void> persistAndWait(AgentCheckpoint checkpoint) {
        PersistenceEvent event = PersistenceEvent.critical(
                checkpoint.getSessionId(),
                SecurityEventType.CHECKPOINT_CREATED,
                em -> em.merge(checkpoint));
        submitCritical(event);
        return event.getFuture();
    }

    // -------------------------------------------------------------------------
    // Test / idle helpers
    // -------------------------------------------------------------------------

    /**
     * Blocks until all currently queued events have been processed.
     * <strong>For use in tests only.</strong>
     */
    public void waitForIdle() {
        PersistenceRetryEngine.sleepQuietly(10); // give any in-flight enqueue time to land
        while (!criticalQueue.isEmpty()
                || !telemetryQueue.isEmpty()
                || processingCritical.get()
                || processingTelemetry.get()) {
            PersistenceRetryEngine.sleepQuietly(10);
        }
    }

    /** For testing only — clears both queues without processing. */
    void clearForTest() {
        criticalQueue.clear();
        telemetryQueue.clear();
    }

    // -------------------------------------------------------------------------
    // Drain loops  (run on Virtual Threads)
    // -------------------------------------------------------------------------

    /**
     * Drain loop for the CRITICAL queue.
     *
     * <p>Uses {@link LinkedBlockingQueue#poll(long, TimeUnit)} with a 100 ms
     * timeout so the {@code running} flag is re-checked regularly and the thread
     * shuts down cleanly on interrupt.
     *
     * <p>ComplianceViolationException is caught here (not re-thrown) because the
     * drain thread must continue processing subsequent events; the caller has
     * already been unblocked via {@code event.getFuture().completeExceptionally(...)}.
     */
    private void drainCritical() {
        PersistenceEvent event = null;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                event = criticalQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;

                processingCritical.set(true);
                try {
                    processEvent(event); // may throw ComplianceViolationException
                } catch (ComplianceViolationException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IllegalStateException ise
                            && ise.getMessage() != null
                            && ise.getMessage().contains("HMAC key unavailable")) {
                        registry.counter("arcclaw.audit.drain.hmac_failure").increment();
                        log.errorf("Critical audit event lost — check persistence health. "
                                + "Event type: %s, Error: %s", event.getEventType(), e.getMessage());
                        log.debugf(e, "Full stacktrace for critical audit failure");
                    }
                    if (event.getFuture() != null && !event.getFuture().isDone()) {
                        event.getFuture().completeExceptionally(e);
                    }
                } finally {
                    processingCritical.set(false);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("AsyncPersistenceEngine Critical drain interrupted — shutting down.");

            } catch (Exception e) {
                if (running.get() && Arc.container() != null) {
                    log.errorf("AsyncPersistenceEngine Unexpected error in critical drain: %s",
                            e.getMessage());
                    log.debugf(e, "AsyncPersistenceEngine Full stacktrace for unexpected critical drain error");
                }
                // Unblock any waiting caller so it doesn't hang indefinitely.
                if (event != null && event.getFuture() != null && !event.getFuture().isDone()) {
                    event.getFuture().completeExceptionally(e);
                }
            }
        }
    }

    /**
     * Drain loop for the TELEMETRY queue.
     *
     * <p>Mirrors {@link #drainCritical()} but errors are swallowed — TELEMETRY
     * is best-effort and must never crash the drain thread.
     */
    private void drainTelemetry() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                PersistenceEvent event = telemetryQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;

                processingTelemetry.set(true);
                try {
                    processEvent(event);
                } finally {
                    processingTelemetry.set(false);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("AsyncPersistenceEngine Telemetry drain interrupted — shutting down.");

            } catch (Exception e) {
                if (running.get() && Arc.container() != null) {
                    log.errorf("AsyncPersistenceEngine Unexpected error in telemetry drain: %s",
                            e.getMessage());
                    log.debugf(e, "AsyncPersistenceEngine Full stacktrace for telemetry drain error");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-event processing
    // -------------------------------------------------------------------------

    /**
     * Processes a single event:
     * <ol>
     *   <li>Guards against Arc container teardown during shutdown.</li>
     *   <li>Binds the workspace scope for this event's {@code sessionId} so
     *       {@code @WorkspaceScoped} beans resolve correctly inside the JPA write.
     *       <strong>NEVER</strong> wrap the whole consumer thread — it processes
     *       events from many different sessions.</li>
     *   <li>Delegates the retry loop to {@link PersistenceRetryEngine#executeWithRetry}.</li>
     *   <li>Wraps any non-CVE exception in a {@link ComplianceViolationException} so
     *       the drain loop has a single exception type to handle.</li>
     * </ol>
     */
    private void processEvent(PersistenceEvent event) {
        // Guard: Arc container may have been destroyed on shutdown.
        if (Arc.container() == null) {
            log.warnf("AsyncPersistenceEngine Arc container is gone. "
                    + "Discarding event %s for session %s",
                    event.getEventType(), event.getSessionId());
            if (event.getFuture() != null) {
                event.getFuture().completeExceptionally(
                        new RuntimeException("Arc container is null"));
            }
            return;
        }

        // Second-check: container may have gone since the drain loop passed its guard.
        if (!running.get() && Arc.container() == null) {
            log.debugf("AsyncPersistenceEngine Skipping write — shutdown. "
                    + "event=%s session=%s", event.getEventType(), event.getSessionId());
            if (event.getFuture() != null) {
                event.getFuture().complete(null); // unblock cleanly, do NOT throw CVE
            }
            return;
        }

        // Bind workspace scope per-event, then delegate all retry logic to retryEngine.
        try {
            WorkspaceScopeHolder.run(event.getSessionId(),
                    () -> retryEngine.executeWithRetry(event));

        } catch (ComplianceViolationException cve) {
            if (event.getFuture() != null) event.getFuture().completeExceptionally(cve);
            throw cve; // re-throw so drainCritical can log the HMAC-unavailable case

        } catch (Exception e) {
            ComplianceViolationException cve = new ComplianceViolationException(
                    event.getSessionId(), event.getEventType(), e);
            if (event.getFuture() != null) event.getFuture().completeExceptionally(cve);
            throw cve;
        }
    }
}
