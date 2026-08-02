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
    void redactsEnvironmentPrefixedApiKeyAssignments() {
        String redacted = SecretRedactor.redact(
                "AMSHA_GENERIC_OPENAI_API_KEY=secret-value AMSHA_API_KEYS_INTERNAL='another-secret'");

        assertFalse(redacted.contains("secret-value"));
        assertFalse(redacted.contains("another-secret"));
        assertTrue(redacted.contains("AMSHA_GENERIC_OPENAI_API_KEY=" + SecretRedactor.REDACTED));
        assertTrue(redacted.contains("AMSHA_API_KEYS_INTERNAL='" + SecretRedactor.REDACTED));
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

    @Test
    void redactsBareSkApiKeysInExceptionText() {
        String redacted = SecretRedactor.redact("provider failed with key sk-abcdefghijklmnopqrstuvwxyz1234");
        assertFalse(redacted.contains("sk-abcdefghijklmnopqrstuvwxyz1234"));
        assertTrue(redacted.contains(SecretRedactor.REDACTED));
    }

    @Test
    void redactsJwtTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbXNoYSJ9.dGVzdHNpZ25hdHVyZQ";
        String redacted = SecretRedactor.redact("auth failed token=" + jwt);
        assertFalse(redacted.contains(jwt));
        assertTrue(redacted.contains(SecretRedactor.REDACTED));
    }

    @Test
    void redactsJwtWithNonJsonPayloadAndHeader() {
        String jwt = "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl";
        String redacted = SecretRedactor.redact("auth failed token=" + jwt);
        assertFalse(redacted.contains(jwt));
        assertTrue(redacted.contains(SecretRedactor.REDACTED));
    }
}
