package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LlmProviderFactoryTest {

    @Inject LlmModelFactory factory;

    @Inject @Any Instance<RoutingChatModelProvider> factories;

    @Test
    void groqProviderIsDiscoveredViaSpi() {
        Exception e = assertThrows(IllegalStateException.class, () -> {
            // Groq provider will throw missing apiKey exception since we passed empty,
            // which means the spi correctly routed it to GroqLlmProviderFactory.
            factory.createModel("groq", "https://api.groq.com", "", "model", 100, null, 1, null, null);
        });
        assertTrue(e.getMessage().contains("Groq provider requires an API key"));
    }

    @Test
    void unknownProviderThrowsIllegalStateWithHelpfulMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            factory.createModel("anthropic", "https://api.anthropic.com", "key", "claude-3", 100, null, 1, null, null);
        });
        
        String message = e.getMessage();
        assertTrue(message.contains("anthropic"));
        assertTrue(message.contains("Registered:"));
    }

    @Test
    void allThreeBuiltInProviderIdsAreRegistered() {
        var ids = factories.stream().map(RoutingChatModelProvider::providerId).collect(Collectors.toSet());
        assertTrue(ids.contains("groq"));
        assertTrue(ids.contains("openai"));
        assertTrue(ids.contains("ollama"));
    }
}
