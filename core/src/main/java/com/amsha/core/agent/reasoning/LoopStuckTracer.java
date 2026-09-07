package com.amsha.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;

import org.jboss.logging.Logger;

/**
 * Emits the {@code loop_stuck} execution-trace event when the loop-stuck detector reports a
 * non-progressing repetition. Kept as a dedicated bean so the (already large)
 * {@code ReasoningMessageAssembler} stays focused on prompt assembly and this trace concern has a
 * single, testable home.
 *
 * <p>Transaction-free: the trace publisher never runs inside a transaction, and emitting is
 * best-effort so a missing publisher can never alter prompt assembly.
 */
@ApplicationScoped
public class LoopStuckTracer {

    private static final Logger log = Logger.getLogger(LoopStuckTracer.class);

    private final TracePublisher tracePublisher;

    @Inject
    public LoopStuckTracer(TracePublisher tracePublisher) {
        this.tracePublisher = tracePublisher;
    }

    /** Emits {@code loop_stuck} for {@code sessionId}; no-op when the publisher is absent or the session is null. */
    public void emit(String sessionId, String agentRole) {
        if (sessionId == null || tracePublisher == null) {
            return;
        }
        tracePublisher.publish(TracePublisher.newEvent(sessionId, TraceStage.LOOP_STUCK)
                .withStation(StationCode.STATION_06_REASONING)
                .withStatus("STUCK")
                .withDetail(agentRole));
    }
}
