package com.amsha.core.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import com.amsha.core.agent.sse.AgentSseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

/**
 * Wire-contract tests for {@link AgentTraceEvent}.
 *
 * <p>The contract must stay additive: it joins the existing {@code SseEvent} union without
 * changing how {@code HitlEvent}, {@code AgentStatusEvent} or {@code HeartbeatEvent} serialize.
 */
class AgentTraceEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static AgentTraceEvent sampleEvent() {
        return new AgentTraceEvent(
                "event-1",
                "run-1",
                "session-1",
                3,
                7L,
                TraceStage.GUARD_CHECK,
                StationCode.STATION_05_TOOL_GUARD,
                "HITL_REQUIRED",
                "HIGH",
                "execute_shell requires approval",
                new AgentTraceEvent.TraceMetrics(120, 40, 250L, 2, 1),
                Instant.parse("2026-09-01T10:15:30Z"));
    }

    @Test
    void serializesTraceDiscriminatorAndVersion() throws Exception {
        String json = objectMapper.writeValueAsString(sampleEvent());

        assertTrue(json.contains("\"eventType\":\"AGENT_TRACE\""), json);
        assertTrue(json.contains("\"version\":1"), json);
    }

    @Test
    void serializesEnumsAsLowercaseSnakeCase() throws Exception {
        String json = objectMapper.writeValueAsString(sampleEvent());

        assertTrue(json.contains("\"stage\":\"guard_check\""), json);
        assertTrue(json.contains("\"station\":\"station_05_tool_guard\""), json);
        assertFalse(json.contains("GUARD_CHECK"), json);
        assertFalse(json.contains("STATION_05_TOOL_GUARD"), json);
    }

    @Test
    void roundTripsEveryComponent() throws Exception {
        AgentTraceEvent event = sampleEvent();
        String json = objectMapper.writeValueAsString(event);

        AgentTraceEvent decoded = objectMapper.readValue(json, AgentTraceEvent.class);

        assertEquals(event, decoded);
        assertEquals(TraceStage.GUARD_CHECK, decoded.stage());
        assertEquals(StationCode.STATION_05_TOOL_GUARD, decoded.station());
        assertEquals("HITL_REQUIRED", decoded.status());
        assertEquals("HIGH", decoded.riskLevel());
        assertEquals(120, decoded.metrics().promptTokens());
        assertEquals(250L, decoded.metrics().durationMs());
        assertEquals(Instant.parse("2026-09-01T10:15:30Z"), decoded.timestamp());
    }

    @Test
    void existingSseVariantsKeepTheirLegacyShape() throws Exception {
        AgentSseService.HitlEvent hitl = new AgentSseService.HitlEvent(
                "session-1",
                "AWAITING_APPROVAL",
                "execute_shell",
                "{}",
                "HIGH",
                null,
                Instant.parse("2026-09-01T10:15:30Z"));
        AgentSseService.AgentStatusEvent status = new AgentSseService.AgentStatusEvent(
                "session-1", "COORDINATOR", "RUNNING", Instant.parse("2026-09-01T10:15:30Z"));
        AgentSseService.HeartbeatEvent heartbeat =
                new AgentSseService.HeartbeatEvent(Instant.parse("2026-09-01T10:15:30Z"));

        for (String json : new String[] {
            objectMapper.writeValueAsString(hitl),
            objectMapper.writeValueAsString(status),
            objectMapper.writeValueAsString(heartbeat)
        }) {
            assertFalse(json.contains("eventType"), json);
            assertFalse(json.contains("\"version\""), json);
        }

        assertEquals(
                "AWAITING_APPROVAL",
                objectMapper
                        .readValue(objectMapper.writeValueAsString(hitl), AgentSseService.HitlEvent.class)
                        .type());
        assertEquals(
                "RUNNING",
                objectMapper
                        .readValue(objectMapper.writeValueAsString(status), AgentSseService.AgentStatusEvent.class)
                        .status());
    }

    @Test
    void traceEventIsAssignableToTheSseUnion() {
        assertTrue(sampleEvent() instanceof AgentSseService.SseEvent);
    }

    @Test
    void factoryGeneratesUniqueEventIdsForTheSameRun() {
        AgentTraceEvent first = AgentTraceEvent.of("run-1", "session-1", 1, 0, TraceStage.TURN_START);
        AgentTraceEvent second = AgentTraceEvent.of("run-1", "session-1", 1, 1, TraceStage.PREFLIGHT);

        assertEquals("run-1", first.runId());
        assertEquals("run-1", second.runId());
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(0L, first.sequence());
        assertEquals(1L, second.sequence());
    }

    @Test
    void rejectsEventsMissingMandatoryIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTraceEvent(
                        "",
                        "run-1",
                        "session-1",
                        1,
                        0,
                        TraceStage.TURN_START,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTraceEvent(
                        "event-1",
                        "",
                        "session-1",
                        1,
                        0,
                        TraceStage.TURN_START,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTraceEvent(
                        "event-1",
                        "run-1",
                        "",
                        1,
                        0,
                        TraceStage.TURN_START,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTraceEvent(
                        "event-1", "run-1", "session-1", 1, 0, null, null, null, null, null, null, Instant.now()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentTraceEvent(
                        "event-1",
                        "run-1",
                        "session-1",
                        1,
                        -1,
                        TraceStage.TURN_START,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()));
    }

    @Test
    void rejectsNegativeMetrics() {
        assertThrows(
                IllegalArgumentException.class, () -> new AgentTraceEvent.TraceMetrics(-1, null, null, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new AgentTraceEvent.TraceMetrics(null, -1, null, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new AgentTraceEvent.TraceMetrics(null, null, -1L, null, null));
    }
}
