package com.amsha.core.guardrail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ParseabilityOutputGuardTest {

    private final ParseabilityOutputGuard guard = new ParseabilityOutputGuard();

    @Test
    void nullResponseIsAllowed() {
        var result = guard.validate("sess", "user", "msg", null);
        assertTrue(result.isAllowed());
    }

    @Test
    void blankResponseIsAllowed() {
        var result = guard.validate("sess", "user", "msg", "   ");
        assertTrue(result.isAllowed());
    }

    @Test
    void shortResponseIsAllowed() {
        var result = guard.validate("sess", "user", "msg", "Hello, world!");
        assertTrue(result.isAllowed());
    }

    @Test
    void longResponseIsSanitized() {
        String longResponse = "x".repeat(100_001);
        var result = guard.validate("sess", "user", "msg", longResponse);
        assertTrue(result.isSanitize());
        assertTrue(((GuardDecision.Sanitize) result).sanitizedInput().contains("[truncated"));
    }

    @Test
    void truncationReducesLength() {
        String longResponse = "a".repeat(200_000);
        var result = guard.validate("sess", "user", "msg", longResponse);
        String sanitized = ((GuardDecision.Sanitize) result).sanitizedInput();
        assertTrue(
                sanitized.length() < longResponse.length(),
                "sanitized=" + sanitized.length() + " original=" + longResponse.length());
        assertTrue(sanitized.endsWith("...[truncated by output guard]"));
    }
}
