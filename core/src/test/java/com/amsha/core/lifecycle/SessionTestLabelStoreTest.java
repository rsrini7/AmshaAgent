package com.amsha.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SessionTestLabelStoreTest {
    @Test
    void labelsSurviveNormalReadsAndAreReapedAfterInactivity() {
        SessionTestLabelStore store = new SessionTestLabelStore();
        store.put("session-1", Map.of("task", "expected"));

        assertEquals("expected", store.get("session-1", "task"));
        assertTrue(store.hasSession("session-1"));

        store.reapExpired(Instant.now().minusSeconds(1));
        assertTrue(store.hasSession("session-1"));

        store.reapExpired(Instant.now().plusSeconds(1));
        assertFalse(store.hasSession("session-1"));
    }
}
