package com.amsha.core.agent.trace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Asynchronous, bounded persistence engine for execution trace events.
 *
 * <p>§5.9 transaction-free zone: Drains queued trace events onto a background Virtual Thread
 * and batch-persists them to the configured {@link ExecutionTraceStorePort}.
 * A persistence delay or failure never pins Agroal connections or blocks the agent loop.
 */
@ApplicationScoped
@Unremovable
public class TracePersistenceEngine {

    private static final Logger log = Logger.getLogger(TracePersistenceEngine.class);
    static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 100;
    private static final long POLL_TIMEOUT_MS = 50L;
    private static final long ERROR_BACKOFF_MS = 250L;

    private final LinkedBlockingQueue<ExecutionTraceDto> queue;
    private final Instance<ExecutionTraceStorePort> traceStoreInstance;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedEvents = new AtomicLong(0);

    private Thread drainThread;

    @Inject
    public TracePersistenceEngine(
            Instance<ExecutionTraceStorePort> traceStoreInstance,
            @ConfigProperty(name = "amsha.trace.persistence.queue-capacity", defaultValue = "10000") int capacity) {
        this.traceStoreInstance = traceStoreInstance;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, capacity));
    }

    protected TracePersistenceEngine() {
        this(null, DEFAULT_QUEUE_CAPACITY);
    }

    void onStartup(@Observes StartupEvent event) {
        if (traceStoreInstance == null || traceStoreInstance.isUnsatisfied()) {
            log.debug("TracePersistenceEngine: No ExecutionTraceStorePort available; drain thread not started.");
            return;
        }
        drainThread = Thread.ofVirtual()
                .inheritInheritableThreadLocals(false)
                .name("amsha-trace-persistence-drain")
                .start(this::drainLoop);
        log.info("TracePersistenceEngine: Background drain thread started on VirtualThread.");
    }

    void onShutdown(@Observes ShutdownEvent event) {
        running.set(false);
        if (drainThread != null) {
            drainThread.interrupt();
            try {
                drainThread.join(5000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            if (drainThread.isAlive()) {
                log.warn("TracePersistenceEngine: drain thread did not terminate within 5s shutdown window");
            }
        } else {
            drainRemaining();
        }
    }

    /**
     * Converts a redacted trace event to an immutable transfer DTO and enqueues it.
     * Never blocks or throws; drops on queue overflow.
     */
    public void enqueue(AgentTraceEvent event) {
        if (event == null) {
            return;
        }
        ExecutionTraceDto dto = toDto(event);
        if (!queue.offer(dto)) {
            long dropped = droppedEvents.incrementAndGet();
            if (dropped % 1000 == 1) {
                log.warnf("TracePersistenceEngine: Queue full (%d). Dropped %d trace events.", queue.size(), dropped);
            }
        }
    }

    private void drainLoop() {
        List<ExecutionTraceDto> batch = new ArrayList<>(BATCH_SIZE);
        try {
            while (running.get()) {
                try {
                    ExecutionTraceDto first = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        batch.add(first);
                        queue.drainTo(batch, BATCH_SIZE - 1);
                        persistBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException _) {
                    break;
                } catch (Throwable t) {
                    // Catch Throwable (not just Exception) so a fatal Error from the store does
                    // not kill the drain thread. Count the unpersisted batch as dropped and
                    // back off so a persistently failing store cannot hot-spin on every poll.
                    log.warnf("drain error: %s", LogUtil.rootMessage(t));
                    droppedEvents.addAndGet(batch.size());
                    batch.clear();
                    backoff();
                }
            }
        } finally {
            // Exclusively drain any remaining queued events on this worker thread. Runs even
            // when the loop exits abnormally, so a fatal drain error still flushes the queue.
            drainRemaining();
        }
    }

    /** Pauses after a drain failure so a persistently failing store cannot hot-spin. */
    private void backoff() {
        try {
            Thread.sleep(ERROR_BACKOFF_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainRemaining() {
        if (queue.isEmpty() || traceStoreInstance == null || traceStoreInstance.isUnsatisfied()) {
            return;
        }
        List<ExecutionTraceDto> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            persistBatch(remaining);
        }
    }

    private void persistBatch(List<ExecutionTraceDto> batch) {
        if (batch.isEmpty() || traceStoreInstance == null || traceStoreInstance.isUnsatisfied()) {
            return;
        }
        List<ExecutionTraceDto> copy = List.copyOf(batch);
        ExecutionTraceStorePort store;
        try {
            store = traceStoreInstance.get();
        } catch (Exception t) {
            log.warnf("TracePersistenceEngine: trace store unavailable: %s", LogUtil.rootMessage(t));
            droppedEvents.addAndGet(copy.size());
            return;
        }
        try {
            store.saveBatch(copy);
        } catch (Exception primaryEx) {
            log.warnf(
                    "TracePersistenceEngine: initial batch persist failed for %d traces: %s; retrying individually",
                    copy.size(), LogUtil.rootMessage(primaryEx));
            int failedItems = 0;
            for (ExecutionTraceDto dto : copy) {
                try {
                    store.save(dto);
                } catch (Exception itemEx) {
                    failedItems++;
                    droppedEvents.incrementAndGet();
                    log.debugf(
                            "TracePersistenceEngine: failed to persist trace event %s: %s",
                            dto.eventId(), LogUtil.rootMessage(itemEx));
                }
            }
            if (failedItems > 0) {
                log.warnf(
                        "TracePersistenceEngine: %d of %d traces could not be persisted and were recorded as dropped",
                        failedItems, copy.size());
            }
        }
    }

    public static ExecutionTraceDto toDto(AgentTraceEvent event) {
        Instant now = Instant.now();
        return new ExecutionTraceDto(
                event.eventId(),
                event.runId(),
                event.sessionId(),
                event.turnNumber(),
                event.sequence(),
                event.stage().wireValue(),
                event.station() != null ? event.station().wireValue() : null,
                event.status(),
                event.riskLevel(),
                event.detail(),
                event.metrics() != null ? event.metrics().promptTokens() : null,
                event.metrics() != null ? event.metrics().completionTokens() : null,
                event.metrics() != null ? event.metrics().durationMs() : null,
                event.metrics() != null ? event.metrics().iteration() : null,
                event.metrics() != null ? event.metrics().attempt() : null,
                event.timestamp() != null ? event.timestamp() : now,
                event.paradigm(),
                event.version(),
                now);
    }

    public static AgentTraceEvent toEvent(ExecutionTraceDto dto) {
        TraceStage stage = TraceStage.fromWireValue(dto.stage());
        StationCode station = dto.station() != null ? StationCode.fromWireValue(dto.station()) : null;
        AgentTraceEvent.TraceMetrics metrics = null;
        if (dto.promptTokens() != null
                || dto.completionTokens() != null
                || dto.durationMs() != null
                || dto.iteration() != null
                || dto.attempt() != null) {
            metrics = new AgentTraceEvent.TraceMetrics(
                    dto.promptTokens(), dto.completionTokens(), dto.durationMs(), dto.iteration(), dto.attempt());
        }
        // version is intentionally NOT restored: AgentTraceEvent.version() is a read-only
        // contract constant (CONTRACT_VERSION) and carries no per-event state, so there is no
        // field/setter to populate from the persisted dto.version().
        return new AgentTraceEvent(
                dto.eventId(),
                dto.runId(),
                dto.sessionId(),
                dto.turnNumber(),
                dto.sequence(),
                stage,
                station,
                dto.status(),
                dto.riskLevel(),
                dto.detail(),
                metrics,
                dto.timestamp(),
                dto.paradigm());
    }

    public int queueSize() {
        return queue.size();
    }

    public long droppedCount() {
        return droppedEvents.get();
    }
}
