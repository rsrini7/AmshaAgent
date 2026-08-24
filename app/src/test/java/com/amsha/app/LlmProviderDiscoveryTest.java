package com.amsha.app;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that all 21 concrete LLM providers in {@code amsha-llm-providers}
 * and the {@link ChatModelCreationPort} are discovered by Quarkus Arc CDI at application runtime.
 */
@QuarkusTest
class LlmProviderDiscoveryTest {

    @Inject
    @Any
    Instance<AgentChatModelProvider> agentProviders;

    @Inject
    @Any
    Instance<RoutingChatModelProvider> routingProviders;

    @Inject
    ChatModelCreationPort chatModelCreationPort;

    private static final Set<String> EXPECTED_PROVIDERS = Set.of(
            ModelDefaults.PROVIDER_GROQ,
            ModelDefaults.PROVIDER_OPENAI,
            ModelDefaults.PROVIDER_OLLAMA,
            ModelDefaults.PROVIDER_CLAUDE_CODE,
            ModelDefaults.PROVIDER_OPENROUTER,
            ModelDefaults.PROVIDER_NVIDIA,
            ModelDefaults.PROVIDER_CEREBRAS,
            ModelDefaults.PROVIDER_SARVAM,
            ModelDefaults.PROVIDER_OPENCODE,
            ModelDefaults.PROVIDER_HUGGINGFACE,
            ModelDefaults.PROVIDER_MISTRAL,
            ModelDefaults.PROVIDER_CLOUDFLARE,
            ModelDefaults.PROVIDER_COHERE,
            ModelDefaults.PROVIDER_CODEX,
            ModelDefaults.PROVIDER_AGY,
            ModelDefaults.PROVIDER_KIMCHI,
            ModelDefaults.PROVIDER_FOUNDRY,
            ModelDefaults.PROVIDER_GEMINI,
            ModelDefaults.PROVIDER_OMLX,
            ModelDefaults.PROVIDER_LLAMACPP,
            ModelDefaults.PROVIDER_GENERIC_OPENAI);

    @Test
    void all21AgentChatModelProvidersDiscovered() {
        Set<String> discovered = agentProviders.stream()
                .map(AgentChatModelProvider::providerName)
                .collect(Collectors.toSet());

        for (String expected : EXPECTED_PROVIDERS) {
            assertTrue(
                    discovered.contains(expected),
                    "Expected AgentChatModelProvider for '" + expected + "' to be discovered by CDI");
        }
    }

    @Test
    void all21RoutingChatModelProvidersDiscovered() {
        Set<String> discovered = routingProviders.stream()
                .map(RoutingChatModelProvider::providerId)
                .collect(Collectors.toSet());

        for (String expected : EXPECTED_PROVIDERS) {
            assertTrue(
                    discovered.contains(expected),
                    "Expected RoutingChatModelProvider for '" + expected + "' to be discovered by CDI");
        }
    }

    @Test
    void chatModelCreationPortInjectedAndActive() {
        assertNotNull(chatModelCreationPort, "ChatModelCreationPort bean must be injected");
        for (String expected : EXPECTED_PROVIDERS) {
            assertTrue(
                    chatModelCreationPort.supportsAdapter(expected),
                    "ChatModelCreationPort must support adapter: " + expected);
        }
    }
}
