package com.arcclaw.core.persistence;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.PersistenceAdapterPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

/**
 * Asynchronous persistence engine using background Virtual Threads to drain
 * a {@link LinkedBlockingQueue} of {@link PersistenceEvent}s.
 */
@ApplicationScoped
@Unremovable
public class AsyncPersistenceEngine {

    private static final Logger log = Logger.getLogger(AsyncPersistenceEngine.class);

    private static final int  CRITICAL_QUEUE_CAPACITY  = 500;
    private static final int  TELEMETRY_QUEUE_CAPACITY = 5_000;

    private final LinkedBlockingQueue<PersistenceEvent> criticalQueue  =
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

    private final AtomicBoolean running          = new AtomicBoolean(true);
    private final AtomicBoolean processingCritical  = new AtomicBoolean(false);
    private final AtomicBoolean processingTelemetry = new AtomicBoolean(false);
    private final AtomicInteger inFlightCritical  = new AtomicInteger(0);
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

        if (criticalDrainThread  != null) criticalDrainThread.interrupt();
        if (telemetryDrainThread != null) telemetryDrainThread.interrupt();

        try {
            if (criticalDrainThread  != null) criticalDrainThread.join(Duration.ofMillis(200));
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

        if (!criticalQueue.offer(event)) {
            log.warn("Critical audit queue at capacity - persisting synchronously to avoid data loss");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "critical").increment();
            try {
                processEvent(event);
            } catch (Exception e) {
                throw new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
            }
            return;
        }

        inFlightCritical.incrementAndGet();

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

        if (!telemetryQueue.offer(event)) {
            log.debug("Telemetry audit queue at capacity - dropping event (acceptable)");
            registry.counter("arcclaw.audit.queue.overflow", "tier", "telemetry").increment();
        } else {
            inFlightTelemetry.incrementAndGet();
        }
    }

    /**
     * Blocks until all currently queued events have been processed.
     * <strong>For use in tests only.</strong>
     */
    public void waitForIdle() {
        if (inFlightCritical.get() == 0 && inFlightTelemetry.get() == 0
                && criticalQueue.isEmpty() && telemetryQueue.isEmpty()
                && !processingCritical.get() && !processingTelemetry.get()) {
            return;
        }

        if (criticalDrainThread == null || !criticalDrainThread.isAlive()
                || telemetryDrainThread == null || !telemetryDrainThread.isAlive()) {
            return;
        }

        sleepQuietly(10);
        long start = System.currentTimeMillis();
        while (running.get() && (inFlightCritical.get() > 0
                || inFlightTelemetry.get() > 0
                || !criticalQueue.isEmpty()
                || !telemetryQueue.isEmpty()
                || processingCritical.get()
                || processingTelemetry.get())) {
            sleepQuietly(10);
            if (System.currentTimeMillis() - start > 10000) {
                log.warn("waitForIdle timed out after 10s - possible deadlock or hung drain thread");
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
                    log.errorf("Unexpected error in critical drain: %s", e.getMessage());
                }
                if (event != null && event.getFuture() != null && !event.getFuture().isDone()) {
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
                    log.warnf("TELEMETRY event dropped: %s", e.getMessage());
                }
            }
        }
    }

    private void processEvent(PersistenceEvent event) {
        if (Arc.container() == null) {
            if (event.getFuture() != null) event.getFuture().completeExceptionally(new RuntimeException("Arc null"));
            return;
        }

        int hmacRetry = 0;
        while (true) {
            try {
                WorkspaceScopeHolder.run(event.getSessionId(),
                        () -> persistenceAdapter.execute(
                                event.getSessionId(),
                                event.getEventType().toString(),
                                event.getDbAction()));
                if (event.getFuture() != null) event.getFuture().complete(null);
                break;
            } catch (IllegalStateException e) {
                boolean isHmacFailure = e.getMessage() != null && e.getMessage().contains("HMAC key unavailable");
                if (isHmacFailure && hmacRetry++ < 3) {
                    log.warnf("Audit HMAC key not yet available, retry %d/3 in 100ms", hmacRetry);
                    sleepQuietly(100);
                    continue;
                }
                
                // RESTORED: Increment failure counter on HMAC exhaustion
                if (isHmacFailure) {
                    registry.counter("arcclaw.audit.drain.hmac_failure").increment();
                }

                ComplianceViolationException cve = new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
                if (event.getFuture() != null) event.getFuture().completeExceptionally(cve);
                throw cve;
            } catch (Exception e) {
                ComplianceViolationException cve = new ComplianceViolationException(event.getSessionId(), event.getEventType(), e);
                if (event.getFuture() != null) event.getFuture().completeExceptionally(cve);
                throw cve;
            }
        }
    }
}
