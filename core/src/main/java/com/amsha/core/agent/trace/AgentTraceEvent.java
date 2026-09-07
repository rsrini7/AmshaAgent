package com.amsha.core.agent.trace;

import java.time.Instant;
import java.util.UUID;

import com.amsha.core.agent.sse.AgentSseService;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Versioned execution-trace payload published on the existing SSE transport.
 *
 * <p>This record <strong>extends, and does not replace</strong>, the {@code SseEvent} union. It
 * implements {@link AgentSseService.SseEvent} so it flows through the same
 * {@code BroadcastProcessor} as {@code HitlEvent}, {@code AgentStatusEvent} and
 * {@code HeartbeatEvent}; existing consumers keep working unchanged because they never matched on
 * {@code eventType}.
 *
 * <p><strong>Honesty invariant:</strong> an event is published only for something the backend
 * actually observed. The UI must render the absence of an event as {@code NOT_INSTRUMENTED} or
 * {@code NOT_OBSERVED} and must never synthesize a step from animation.
 *
 * <p><strong>Disclosure invariant:</strong> every field here is safe by construction. Prompts,
 * completions, secrets, authorization headers, arbitrary tool parameters, audit keys and raw Java
 * source are removed server-side by {@link TraceRedactor} before publication.
 *
 * <p><strong>Transaction invariant (§5.9):</strong> instances are created and published outside
 * any {@code @Transactional} boundary.
 */
public record AgentTraceEvent(
        String eventId,
        String runId,
        String sessionId,
        int turnNumber,
        long sequence,
        TraceStage stage,
        StationCode station,
        String status,
        String riskLevel,
        String detail,
        TraceMetrics metrics,
        Instant timestamp,
        String paradigm)
        implements AgentSseService.SseEvent {

    /** Discriminator that lets existing SSE consumers recognise a trace payload. */
    public static final String EVENT_TYPE = "AGENT_TRACE";

    /** Contract version of this payload; bumped only with a coordinated UI update. */
    public static final int CONTRACT_VERSION = 1;

    public AgentTraceEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage is required");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }

    /** Overload for backwards compatibility with 12-argument callers. */
    public AgentTraceEvent(
            String eventId,
            String runId,
            String sessionId,
            int turnNumber,
            long sequence,
            TraceStage stage,
            StationCode station,
            String status,
            String riskLevel,
            String detail,
            TraceMetrics metrics,
            Instant timestamp) {
        this(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                null);
    }

    /**
     * Serialized discriminator. Read-only: the value is a contract constant, never accepted
     * from the wire.
     */
    @JsonProperty(value = "eventType", access = JsonProperty.Access.READ_ONLY)
    public String eventType() {
        return EVENT_TYPE;
    }

    /** Serialized contract version. Read-only: never accepted from the wire. */
    @JsonProperty(value = "version", access = JsonProperty.Access.READ_ONLY)
    public int version() {
        return CONTRACT_VERSION;
    }

    /**
     * Minimal factory satisfying the mandatory identity fields; optional fields default to
     * {@code null} and {@code timestamp} to {@link Instant#now()}.
     */
    public static AgentTraceEvent of(String runId, String sessionId, int turnNumber, long sequence, TraceStage stage) {
        return new AgentTraceEvent(
                UUID.randomUUID().toString(),
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                null);
    }

    /** Returns a copy with the given backend paradigm, leaving every other component unchanged. */
    public AgentTraceEvent withParadigm(String paradigm) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /** Returns a copy with the given station, leaving every other component unchanged. */
    public AgentTraceEvent withStation(StationCode station) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /** Returns a copy with the given status, leaving every other component unchanged. */
    public AgentTraceEvent withStatus(String status) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /** Returns a copy with the given risk level, leaving every other component unchanged. */
    public AgentTraceEvent withRiskLevel(String riskLevel) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /** Returns a copy with the given detail, leaving every other component unchanged. */
    public AgentTraceEvent withDetail(String detail) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /** Returns a copy with the given metrics, leaving every other component unchanged. */
    public AgentTraceEvent withMetrics(TraceMetrics metrics) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /**
     * Returns a copy with the given intra-run ordering position. Assigned by
     * {@link TracePublisher} so the UI can order events that arrive out of order on reconnect.
     */
    public AgentTraceEvent withSequence(long sequence) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /** Returns a copy with the given turn number, leaving every other component unchanged. */
    public AgentTraceEvent withTurnNumber(int turnNumber) {
        return new AgentTraceEvent(
                eventId,
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                station,
                status,
                riskLevel,
                detail,
                metrics,
                timestamp,
                paradigm);
    }

    /**
     * Numeric telemetry attached to a trace event.
     *
     * <p>Deliberately carries counts and durations only — never content. This is the only part of
     * the contract allowed to describe model usage, and it stays free of prompts, completions and
     * tool parameters.
     *
     * <p>Registered for native reflection through {@code classNames} with the binary name
     * {@code com.amsha.core.agent.trace.AgentTraceEvent$TraceMetrics}.
     */
    public record TraceMetrics(
            Integer promptTokens, Integer completionTokens, Long durationMs, Integer iteration, Integer attempt) {
        public TraceMetrics {
            if (promptTokens != null && promptTokens < 0) {
                throw new IllegalArgumentException("promptTokens must not be negative");
            }
            if (completionTokens != null && completionTokens < 0) {
                throw new IllegalArgumentException("completionTokens must not be negative");
            }
            if (durationMs != null && durationMs < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
            if (iteration != null && iteration < 0) {
                throw new IllegalArgumentException("iteration must not be negative");
            }
            if (attempt != null && attempt < 0) {
                throw new IllegalArgumentException("attempt must not be negative");
            }
        }
    }
}
