package com.amsha.selfevolve.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class EvolutionEventTest {

    @Test
    void create_generatesIdAndTimestamp() {
        EvolutionEvent event = EvolutionEvent.create(
                "session1", "user1", EvolutionEventType.USER_MESSAGE, "coordinator", "hello", null, 1, null);

        assertNotNull(event.eventId());
        assertTrue(!event.eventId().isEmpty());
        assertEquals("session1", event.sessionId());
        assertEquals("user1", event.userId());
        assertEquals(EvolutionEventType.USER_MESSAGE, event.eventType());
        assertEquals("coordinator", event.role());
        assertEquals("hello", event.content());
        assertNull(event.toolName());
        assertEquals(1, event.turnNumber());
        assertNull(event.metadataJson());
        assertTrue(!event.createdAt().isAfter(Instant.now()));
    }

    @Test
    void create_withToolName_preservesToolName() {
        EvolutionEvent event = EvolutionEvent.create(
                "s1", "u1", EvolutionEventType.TOOL_CALL, "coder", "exec code", "sandbox_exec", 2, null);

        assertEquals("sandbox_exec", event.toolName());
        assertEquals(EvolutionEventType.TOOL_CALL, event.eventType());
    }

    @Test
    void compactConstructor_nullEventId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvolutionEvent(
                        null,
                        "s1",
                        "u1",
                        EvolutionEventType.USER_MESSAGE,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()));
    }

    @Test
    void compactConstructor_blankSessionId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvolutionEvent(
                        "id", "", "u1", EvolutionEventType.USER_MESSAGE, null, null, null, null, null, Instant.now()));
    }

    @Test
    void compactConstructor_nullEventType_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvolutionEvent("id", "s1", "u1", null, null, null, null, null, null, Instant.now()));
    }

    @Test
    void compactConstructor_nullCreatedAt_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvolutionEvent(
                        "id", "s1", "u1", EvolutionEventType.USER_MESSAGE, null, null, null, null, null, null));
    }

    @Test
    void allEventTypes_exist() {
        assertEquals(9, EvolutionEventType.values().length);
        assertNotNull(EvolutionEventType.USER_MESSAGE);
        assertNotNull(EvolutionEventType.AGENT_MESSAGE);
        assertNotNull(EvolutionEventType.TOOL_CALL);
        assertNotNull(EvolutionEventType.TOOL_RESULT);
        assertNotNull(EvolutionEventType.TOOL_ERROR);
        assertNotNull(EvolutionEventType.RESOLUTION);
        assertNotNull(EvolutionEventType.ESCALATION);
        assertNotNull(EvolutionEventType.COMPACTION);
        assertNotNull(EvolutionEventType.SELF_CORRECTION);
    }
}
