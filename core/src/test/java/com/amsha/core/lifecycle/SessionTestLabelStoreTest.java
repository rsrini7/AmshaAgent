package com.amsha.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SessionTestLabelStoreTest {

    @Test
    void putGetAndClearAreIdempotent() {
        SessionTestLabelStore store = new SessionTestLabelStore();
        store.put("s1", Map.of("tool_risk", "LOW_RISK"));
        assertEquals("LOW_RISK", store.get("s1", "tool_risk"));
        assertTrue(store.hasSession("s1"));

        store.clearSession("s1");
        store.clearSession("s1");
        assertNull(store.get("s1", "tool_risk"));
        assertFalse(store.hasSession("s1"));
    }
}
