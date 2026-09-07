package com.amsha.onboarding.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingI18nServiceTest {

    private OnboardingI18nService service;

    @BeforeEach
    void setUp() {
        service = new OnboardingI18nService();
    }

    @Test
    void resolvesExistingEnglishKeys() {
        assertTrue(service.hasKey("onboarding.welcome.title"));
        assertEquals("Welcome to Amsha Agent", service.resolve("onboarding.welcome.title"));
        assertNotNull(service.resolve("onboarding.connections.title"));
        assertNotNull(service.resolve("onboarding.mcp.name.title"));
    }

    @Test
    void fallsBackToEnglishWhenSpecificLocaleKeyMissing() {
        // French locale falls back to English bundle
        String title = service.resolve("onboarding.welcome.title", Locale.FRENCH);
        assertEquals("Welcome to Amsha Agent", title);
    }

    @Test
    void returnsEmptyForMissingKeys() {
        assertFalse(service.hasKey("non.existent.key.12345"));
        assertTrue(service.find("non.existent.key.12345").isEmpty());
    }

    @Test
    void requireKeyThrowsOnMissingKey() {
        assertThrows(IllegalArgumentException.class, () -> service.requireKey("completely.bogus.key"));
    }

    @Test
    void requireKeySucceedsOnExistingKey() {
        service.requireKey("onboarding.welcome.title");
    }
}
