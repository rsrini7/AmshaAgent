package com.amsha.selfevolve.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.FlushReason;

import org.junit.jupiter.api.Test;

class InMemoryConversationBufferTest {

    private EvolutionEvent event(String sessionId) {
        return EvolutionEvent.create(
                sessionId, "user1", EvolutionEventType.USER_MESSAGE, "coordinator", "hello", null, 1, null);
    }

    @Test
    void append_returnsFalseWhenBelowThreshold() {
        var buffer = new InMemoryConversationBuffer("s1", 3);
        assertFalse(buffer.append(event("s1")));
        assertEquals(1, buffer.size());
    }

    @Test
    void append_returnsTrueWhenAtThreshold() {
        var buffer = new InMemoryConversationBuffer("s1", 2);
        buffer.append(event("s1"));
        assertTrue(buffer.append(event("s1")));
    }

    @Test
    void drain_returnsAllEventsAndResets() {
        var buffer = new InMemoryConversationBuffer("s1", 10);
        buffer.append(event("s1"));
        buffer.append(event("s1"));
        List<EvolutionEvent> drained = buffer.drain(FlushReason.SESSION_END);
        assertEquals(2, drained.size());
        assertEquals(0, buffer.size());
        assertFalse(buffer.hasEvents());
    }

    @Test
    void drain_emptyBuffer_returnsEmptyList() {
        var buffer = new InMemoryConversationBuffer("s1", 10);
        List<EvolutionEvent> drained = buffer.drain(FlushReason.EVENT_THRESHOLD);
        assertTrue(drained.isEmpty());
    }

    @Test
    void sessionId_returnsBoundSession() {
        var buffer = new InMemoryConversationBuffer("my-session", 5);
        assertEquals("my-session", buffer.sessionId());
    }

    @Test
    void constructor_blankSessionId_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryConversationBuffer("", 5));
    }

    @Test
    void constructor_zeroMaxEvents_throws() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryConversationBuffer("s1", 0));
    }

    @Test
    void append_nullEvent_throws() {
        var buffer = new InMemoryConversationBuffer("s1", 5);
        assertThrows(IllegalArgumentException.class, () -> buffer.append(null));
    }
}
