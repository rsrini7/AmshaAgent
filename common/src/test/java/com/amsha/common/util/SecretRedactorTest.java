package com.amsha.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    private static final String TELEGRAM_TOKEN = "123456789:AAExampleTelegramCredential_123456789";

    @Test
    void redactsTelegramApiUrlsAndStandaloneTokens() {
        String value = "GET https://api.telegram.org/bot" + TELEGRAM_TOKEN + "/getUpdates token=" + TELEGRAM_TOKEN;

        String redacted = SecretRedactor.redact(value);

        assertFalse(redacted.contains(TELEGRAM_TOKEN));
        assertTrue(redacted.contains("https://api.telegram.org/bot" + SecretRedactor.REDACTED));
    }

    @Test
    void redactsBearerAndNamedSecrets() {
        String redacted = SecretRedactor.redact(
                "Authorization: Bearer abcdefghijklmnopqrstuvwxyz api_key=super-secret-value password='hunter2'");

        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz"));
        assertFalse(redacted.contains("super-secret-value"));
        assertFalse(redacted.contains("hunter2"));
    }

    @Test
    void preservesOrdinaryTextAndNull() {
        assertEquals("ordinary diagnostic", SecretRedactor.redact("ordinary diagnostic"));
        assertEquals("apikey", SecretRedactor.redact("apikey"));
        assertNull(SecretRedactor.redact(null));
    }

    @Test
    void redactsSensitiveAssignmentAtMinimumLengthBoundary() {
        assertEquals("apikey=" + SecretRedactor.REDACTED, SecretRedactor.redact("apikey=x"));
    }
}
