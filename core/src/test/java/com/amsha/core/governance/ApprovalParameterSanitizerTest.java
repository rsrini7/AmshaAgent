package com.amsha.core.governance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ApprovalParameterSanitizerTest {

    @Test
    void redactsSensitiveKeysRecursively() {
        String display = ApprovalParameterSanitizer.toJson(Map.of(
                "url", "https://example.com",
                "headers", Map.of("Authorization", "Bearer secret"),
                "nested", List.of(Map.of("apiKey", "secret-key"))));

        assertTrue(display.contains("https://example.com"));
        assertTrue(display.contains("[REDACTED]"));
        assertFalse(display.contains("Bearer secret"));
        assertFalse(display.contains("secret-key"));
    }

    @Test
    void truncatesLongDisplayStrings() {
        String display = ApprovalParameterSanitizer.toJson(Map.of("query", "x".repeat(600)));

        assertTrue(display.contains("[truncated]"));
        assertTrue(display.length() < 600);
    }
}
