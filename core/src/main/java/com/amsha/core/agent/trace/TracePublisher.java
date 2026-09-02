package com.amsha.core.agent.trace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.sse.AgentSseService;

import org.jboss.logging.Logger;

/**
 * Publishes {@link AgentTraceEvent}s onto the existing SSE transport.
 *
 * <p>This is the <strong>only</strong> component allowed to broadcast trace events, and it is
 * deliberately separate from {@code ObservabilityManager}, which keeps its OpenTelemetry-context
 * and observer-callback responsibilities.
 *
 * <h3>§5.9 transaction-free zone</h3>
 * No method here carries {@code @Transactional}. Trace hooks sit inside the agent loop, which
 * must never pin an Agroal connection while an LLM call or tool execution is in flight; a
 * transactional publisher would reintroduce the SQLITE_BUSY cascade. Callers already inside a
 * transaction must not publish — {@code CheckpointApprovalService} keeps
 * {@code NOT_SUPPORTED} for exactly this reason.
 *
 * <h3>Failure isolation</h3>
 * Publishing is best-effort. A trace failure — including a missing subscriber, a serialization
 * problem, or a redaction error — is logged at debug level and swallowed, so observability can
 * never change the outcome of an agent turn.
 */
@ApplicationScoped
public class TracePublisher {

    private static final Logger log = Logger.getLogger(TracePublisher.class);

    /** Upper bound on concurrently tracked runs, so a long-lived process cannot leak. */
    static final int MAX_TRACKED_RUNS = 512;

    private static final String RUN_ID_SEPARATOR = ":turn-";

    /** Default fallback turn number when no active turn has been registered. */
    public static final int DEFAULT_TURN_NUMBER = 1;

    private static final ConcurrentHashMap<String, AtomicInteger> sessionTurns = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<String> sessionOrder = new ConcurrentLinkedDeque<>();

    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> runOrder = new ConcurrentLinkedDeque<>();

    private final AgentSseService agentSseService;
    private final TraceRedactor redactor;

    @Inject
    public TracePublisher(AgentSseService agentSseService, TraceRedactor redactor) {
        this.agentSseService = agentSseService;
        this.redactor = redactor;
    }

    /**
     * Advances and returns the turn number for {@code sessionId}.
     * Call at the start of each agent turn (TURN_START).
     */
    public static int startTurn(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return DEFAULT_TURN_NUMBER;
        }
        AtomicInteger created = new AtomicInteger(1);
        AtomicInteger existing = sessionTurns.putIfAbsent(sessionId, created);
        if (existing == null) {
            evictOldestSessionsIfNeeded();
            sessionOrder.addLast(sessionId);
            return 1;
        }
        return existing.incrementAndGet();
    }

    /**
     * Returns the current active turn number for {@code sessionId}, or {@link #DEFAULT_TURN_NUMBER}
     * if no turn has been explicitly started.
     */
    public static int currentTurnNumber(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return DEFAULT_TURN_NUMBER;
        }
        AtomicInteger counter = sessionTurns.get(sessionId);
        return counter != null ? counter.get() : DEFAULT_TURN_NUMBER;
    }

    /** Cleans up turn-tracking state when a session is finalized or reaped. */
    public static void releaseSession(String sessionId) {
        if (sessionId != null) {
            sessionTurns.remove(sessionId);
            sessionOrder.remove(sessionId);
        }
    }

    private static void evictOldestSessionsIfNeeded() {
        while (sessionTurns.size() >= MAX_TRACKED_RUNS) {
            String oldest = sessionOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            sessionTurns.remove(oldest);
        }
    }

    /**
     * Builds the identifier that groups every event of one logical turn. Derived deterministically
     * from {@code (sessionId, turnNumber)} so no extra state has to be threaded through the loop.
     */
    public static String runId(String sessionId, int turnNumber) {
        return sessionId + RUN_ID_SEPARATOR + Math.max(DEFAULT_TURN_NUMBER, turnNumber);
    }

    /** Convenience overload using {@link #currentTurnNumber(String)}. */
    public static String runId(String sessionId) {
        return runId(sessionId, currentTurnNumber(sessionId));
    }

    /**
     * Builds a prototype event stamped with the current turn number, run ID, and paradigm metadata.
     */
    public static AgentTraceEvent newEvent(String sessionId, TraceStage stage, String paradigm) {
        int turn = currentTurnNumber(sessionId);
        AgentTraceEvent ev = AgentTraceEvent.of(runId(sessionId, turn), sessionId, turn, 0, stage);
        return paradigm != null ? ev.withParadigm(paradigm) : ev;
    }

    /**
     * Builds a prototype event stamped with the current turn number and corresponding run ID.
     */
    public static AgentTraceEvent newEvent(String sessionId, TraceStage stage) {
        return newEvent(sessionId, stage, null);
    }

    /**
     * Redacts, sequences and broadcasts an event.
     *
     * <p>Redaction happens here rather than at the call site so no instrumentation point can
     * accidentally publish an unsanitized payload. Events are dropped silently when no SSE
     * subscriber exists — the trace is a bounded live stream, not durable storage (Issue 07).
     *
     * @param event the event to publish; {@code null} is ignored
     */
    public void publish(AgentTraceEvent event) {
        if (event == null) {
            return;
        }
        try {
            AgentTraceEvent sequenced = event.withSequence(nextSequence(event.runId()));
            agentSseService.publish(sequenced.sessionId(), redactor.redact(sequenced));
        } catch (RuntimeException | LinkageError e) {
            log.debugf(
                    "TracePublisher: dropped trace event for session %s: %s",
                    event.sessionId(), LogUtil.rootMessage(e));
        }
    }

    /**
     * Releases the sequence counter for a completed run. Safe to call more than once.
     */
    public void endRun(String runId) {
        if (runId == null) {
            return;
        }
        sequences.remove(runId);
        runOrder.remove(runId);
    }

    /** Number of runs currently holding a sequence counter; exposed for tests and metrics. */
    public int trackedRunCount() {
        return sequences.size();
    }

    private long nextSequence(String runId) {
        AtomicLong counter = sequences.get(runId);
        if (counter == null) {
            evictOldestIfNeeded();
            AtomicLong created = new AtomicLong();
            AtomicLong existing = sequences.putIfAbsent(runId, created);
            counter = existing != null ? existing : created;
            if (existing == null) {
                runOrder.addLast(runId);
            }
        }
        return counter.getAndIncrement();
    }

    private void evictOldestIfNeeded() {
        while (sequences.size() >= MAX_TRACKED_RUNS) {
            String oldest = runOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            sequences.remove(oldest);
        }
    }
}
