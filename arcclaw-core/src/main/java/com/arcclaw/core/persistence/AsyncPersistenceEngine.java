package com.arcclaw.core.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.util.BackoffRetryExecutor;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.PersistenceAdapterPort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Asynchronous persistence engine using background Virtual Threads to drain
 * a {@link LinkedBlockingQueue} of {@link PersistenceEvent}s.
 */
@ApplicationScoped
@Unremovable
public class AsyncPersistenceEngine {

    private static final Logger log = Logger.getLogger(AsyncPersistenceEngine.class);

    private static final int CRITICAL_QUEUE_CAPACITY = 500;
    private static final int TELEMETRY_QUEUE_CAPACITY = 5_000;

    private final LinkedBlockingQueue<PersistenceEvent> criticalQueue =
            new LinkedBlockingQueue<>(CRITICAL_QUEUE_CAPACITY);

    private final LinkedBlockingQueue<PersistenceEvent> telemetryQueue =
            new LinkedBlockingQueue<>(TELEMETRY_QUEUE_CAPACITY);

    @Inject
    MeterRegistry registry;

    /** Handles backend-specific retry-loop and transaction boundary. */
    @Inject
    PersistenceAdapterPort persistenceAdapter;

    @Inject
    CheckpointStorePort checkpointStore;

    @ConfigProperty(name = "arcclaw.audit.idle-timeout-ms", defaultValue = "10000")
    long idleTimeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean processingCritical = new AtomicBoolean(false);
    private final AtomicBoolean processingTelemetry = new AtomicBoolean(false);
    private final AtomicInteger inFlightCritical = new AtomicInteger(0);
    private final AtomicInteger inFlightTelemetry = new AtomicInteger(0);

    private Thread criticalDrainThread;
    private Thread telemetryDrainThread;

    void startDrainThreads(@Observes StartupEvent ev) {
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

        if (criticalDrainThread != null) criticalDrainThread.interrupt();
        if (telemetryDrainThread != null) telemetryDrainThread.interrupt();

        try {
            if (criticalDrainThread != null) criticalDrainThread.join(Duration.ofMillis(200));
            if (telemetryDrainThread != null) telemetryDrainThread.join(Duration.ofMillis(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("AsyncPersistenceEngine Shutdown complete.");
    }

    public void submitCritical(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.CRITICAL) {
            throw new IllegalArgumentException("submitCritical requires a CRITICAL event");
        }

        // Drain threads not yet started (pre-StartupEvent) — process synchronously to avoid deadlock
        boolean drainReady = criticalDrainThread != null && criticalDrainThread.isAlive();
        if (!drainReady) {
            try {
                processEvent(event);
            } catch (Exception e) {
                throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
            }
            return;
        }

        inFlightCritical.incrementAndGet();
        if (!criticalQueue.offer(event)) {
            inFlightCritical.decrementAndGet();
            log.warn("Critical audit queue at capacity - persisting synchronously to avoid data loss");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "critical").increment();
            try {
                processEvent(event);
            } catch (Exception e) {
                throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
            }
            return;
        }

        try {
            event.getFuture().join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof ComplianceViolationException cve) throw cve;
            throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), cause);
        }
    }

    public void submitTelemetry(PersistenceEvent event) {
        if (event.getTier() != PersistenceEvent.Tier.TELEMETRY) {
            throw new IllegalArgumentException("submitTelemetry requires a TELEMETRY event");
        }

        inFlightTelemetry.incrementAndGet();
        if (!telemetryQueue.offer(event)) {
            inFlightTelemetry.decrementAndGet();
            log.debug("Telemetry audit queue at capacity - dropping event (acceptable)");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "telemetry")
                    .increment();
        }
    }

    /**
     * Blocks until all currently queued events have been processed.
     * <strong>For use in tests only.</strong>
     */
    public void waitForIdle() {
        if (inFlightCritical.get() == 0
                && inFlightTelemetry.get() == 0
                && criticalQueue.isEmpty()
                && telemetryQueue.isEmpty()
                && !processingCritical.get()
                && !processingTelemetry.get()) {
            return;
        }

        boolean criticalAlive = criticalDrainThread != null && criticalDrainThread.isAlive();
        boolean telemetryAlive = telemetryDrainThread != null && telemetryDrainThread.isAlive();
        if (!criticalAlive || !telemetryAlive) {
            return;
        }

        sleepQuietly(10);
        long start = System.currentTimeMillis();
        while (running.get()
                && (inFlightCritical.get() > 0
                        || inFlightTelemetry.get() > 0
                        || !criticalQueue.isEmpty()
                        || !telemetryQueue.isEmpty()
                        || processingCritical.get()
                        || processingTelemetry.get())) {
            sleepQuietly(10);
            if (System.currentTimeMillis() - start > idleTimeoutMs) {
                log.warnf("waitForIdle timed out after %dms - possible deadlock or hung drain thread", idleTimeoutMs);
                break;
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void clearForTest() {
        criticalQueue.clear();
        telemetryQueue.clear();
    }

    int getCriticalQueueSize() {
        return criticalQueue.size();
    }

    private void drainCritical() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            PersistenceEvent event = null;
            try {
                event = criticalQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;

                try {
                    processingCritical.set(true);
                    processEvent(event);
                } catch (ComplianceViolationException e) {
                    if (event.getFuture() != null && !event.getFuture().isDone()) {
                        event.getFuture().completeExceptionally(e);
                    }
                } finally {
                    processingCritical.set(false);
                    inFlightCritical.decrementAndGet();
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("AsyncPersistenceEngine Critical drain interrupted - shutting down.");
            } catch (Exception e) {
                if (running.get()) {
                    log.errorf("Unexpected error in critical drain: %s", LogUtil.rootMessage(e));
                    log.debugf("Full stacktrace for critical drain failure: %s", LogUtil.trimmedStack(e));
                }
                if (event != null
                        && event.getFuture() != null
                        && !event.getFuture().isDone()) {
                    event.getFuture().completeExceptionally(e);
                }
            }
        }
    }

    private void drainTelemetry() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            PersistenceEvent event = null;
            try {
                event = telemetryQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;

                try {
                    processingTelemetry.set(true);
                    processEvent(event);
                } finally {
                    processingTelemetry.set(false);
                    inFlightTelemetry.decrementAndGet();
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("AsyncPersistenceEngine Telemetry drain interrupted - shutting down.");
            } catch (Exception e) {
                // telemetry errors are logged and swallowed
                if (running.get()) {
                    log.warnf("TELEMETRY event dropped: %s", LogUtil.rootMessage(e));
                    log.debugf("Full stacktrace for telemetry drop: %s", LogUtil.trimmedStack(e));
                }
            }
        }
    }

    private void processEvent(PersistenceEvent event) {
        if (Arc.container() == null) {
            if (event.getFuture() != null) event.getFuture().completeExceptionally(new RuntimeException("Arc null"));
            return;
        }

        try {
            BackoffRetryExecutor.executeVoid(
                    "audit-drain-" + event.getEventType(),
                    new BackoffRetryExecutor.Strategy(3, 100, 500, 0.0), // Fixed delay-like for HMAC wait
                    e -> e instanceof IllegalStateException
                            && e.getMessage() != null
                            && e.getMessage().contains("HMAC key unavailable"),
                    () -> {
                        String sid = event.getSessionId();
                        if (sid == null || sid.isBlank()) {
                            persistenceAdapter.execute(sid, event.getEventType().toString(), event.getDbAction());
                        } else {
                            WorkspaceScopeHolder.run(
                                    sid,
                                    () -> persistenceAdapter.execute(
                                            sid, event.getEventType().toString(), event.getDbAction()));
                        }
                    });
            if (event.getFuture() != null) event.getFuture().complete(null);
        } catch (Exception e) {
            // Check if it was an HMAC failure that exhausted retries
            boolean isHmacFailure = e instanceof IllegalStateException
                    && e.getMessage() != null
                    && e.getMessage().contains("HMAC key unavailable");
            if (isHmacFailure) {
                registry.counter("arcclaw.audit.drain.hmac_failure").increment();
            }

            ComplianceViolationException cve =
                    new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
            if (event.getFuture() != null) event.getFuture().completeExceptionally(cve);
            throw cve;
        }
    }
}
