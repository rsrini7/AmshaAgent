package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class InfiniteLoopProvidersCoverageTest {

    @Test
    void testCompactionModelThrowsWhenNoProvidersConfigured() {
        InfiniteLoopCompactionChatModelProvider provider = new InfiniteLoopCompactionChatModelProvider();
        provider.loopProviders = "non_existent_provider_1,non_existent_provider_2";
        provider.cooldownBaseSeconds = 10;
        provider.cooldownMaxSeconds = 60;
        provider.cooldownTransientSeconds = 2;
        provider.maxTokens = 512;

        assertThrows(IllegalStateException.class, provider::compactionModel);
    }

    @Test
    void testIntentModelThrowsWhenNoProvidersConfigured() {
        InfiniteLoopIntentChatModelProvider provider = new InfiniteLoopIntentChatModelProvider();
        provider.loopProviders = "non_existent_provider_1,non_existent_provider_2";
        provider.cooldownBaseSeconds = 10;
        provider.cooldownMaxSeconds = 60;
        provider.cooldownTransientSeconds = 2;

        assertThrows(IllegalStateException.class, provider::intentClassifierModel);
    }

    @Test
    void testCompactionSanitizeAccountId() throws Exception {
        Method m = InfiniteLoopCompactionChatModelProvider.class.getDeclaredMethod("sanitizeAccountId", String.class);
        m.setAccessible(true);

        assertEquals("", m.invoke(null, (String) null));
        assertEquals("", m.invoke(null, "   "));
        assertEquals("12345", m.invoke(null, "12345"));
        assertEquals("acc123", m.invoke(null, "accounts/acc123/extra"));
        assertEquals("acc123", m.invoke(null, "acc123/extra"));
    }

    @Test
    void testCompactionNormalizeBaseUrl() throws Exception {
        Method m = InfiniteLoopCompactionChatModelProvider.class.getDeclaredMethod("normalizeBaseUrl", String.class);
        m.setAccessible(true);

        assertNull(m.invoke(null, (String) null));
        assertEquals("   ", m.invoke(null, "   "));
        assertEquals("http://example.com/v1", m.invoke(null, "http://example.com/v1/v1"));
        assertEquals("http://example.com/ai/v1", m.invoke(null, "http://example.com/ai/v1/ai/v1"));
        assertEquals("http://example.com", m.invoke(null, "http://example.com}"));
        assertThrows(Exception.class, () -> m.invoke(null, "http://example.com/bad}after"));
    }
}
