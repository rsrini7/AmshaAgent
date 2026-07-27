package com.amsha.core.guardrail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GuardDecisionTest {

    @Test
    void allowIsAllowed() {
        assertTrue(new GuardDecision.Allow().isAllowed());
    }

    @Test
    void allowIsNotBlockedOrRetryOrSanitize() {
        var d = new GuardDecision.Allow();
        assertFalse(d.isBlocked());
        assertFalse(d.isRetryable());
        assertFalse(d.isSanitize());
    }

    @Test
    void blockIsBlocked() {
        var d = new GuardDecision.Block("reason", "detail");
        assertTrue(d.isBlocked());
        assertEquals("reason", d.reason());
        assertEquals("detail", d.detail());
    }

    @Test
    void blockIsNotAllowed() {
        assertFalse(new GuardDecision.Block("r", "d").isAllowed());
    }

    @Test
    void retryIsRetryable() {
        var d = new GuardDecision.Retry("reason", "corrected");
        assertTrue(d.isRetryable());
        assertEquals("reason", d.reason());
        assertEquals("corrected", d.correctedInput());
    }

    @Test
    void retryIsNotAllowed() {
        assertFalse(new GuardDecision.Retry("r", "c").isAllowed());
    }

    @Test
    void sanitizeIsSanitize() {
        var d = new GuardDecision.Sanitize("cleaned");
        assertTrue(d.isSanitize());
        assertEquals("cleaned", d.sanitizedInput());
    }

    @Test
    void sanitizeIsNotAllowed() {
        assertFalse(new GuardDecision.Sanitize("c").isAllowed());
    }
}
