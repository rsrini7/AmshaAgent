package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LlmProviderOnboardingAdapterTest {

    @Test
    void testDefaultMethodImplementations() {
        ModelEntry model = new ModelEntry("gpt-4o", "GPT-4o");
        LlmProviderOnboardingAdapter adapter = new LlmProviderOnboardingAdapter() {
            @Override
            public List<LlmProviderDescriptor> descriptors() {
                return List.of(new LlmProviderDescriptor(
                        "openai",
                        "OpenAI",
                        "openai",
                        "API_KEY",
                        "http://localhost",
                        true,
                        false,
                        List.of(model),
                        Map.of()));
            }

            @Override
            public ProviderValidationResult validate(ConnectionValidationRequest request) {
                return ProviderValidationResult.ok();
            }
        };

        assertTrue(adapter.descriptor("openai").isPresent());
        assertTrue(adapter.descriptor("OPENAI").isPresent());
        assertFalse(adapter.descriptor("unknown").isPresent());
        assertFalse(adapter.descriptor(null).isPresent());
        assertTrue(adapter.resolveModel("openai", "gpt-4o").isPresent());
        assertFalse(adapter.resolveModel("openai", "unknown-model").isPresent());
        assertFalse(adapter.resolveModel("unknown-provider", "gpt-4o").isPresent());
        assertTrue(adapter.validate(new ConnectionValidationRequest("openai", "gpt-4o", "", true, List.of()))
                .eligible());
    }

    @Test
    void testAgentChatModelProviderDefaultMethods() {
        AgentChatModelProvider provider = new AgentChatModelProvider() {
            @Override
            public String providerName() {
                return "test-prov";
            }

            @Override
            public ChatModel create() {
                return null;
            }
        };

        assertEquals(StructuredOutputMode.NATIVE, provider.structuredOutputMode());
        assertEquals("unknown", provider.baseUrl());
        assertNull(provider.create("custom-model"));
    }

    @Test
    void testRoutingChatModelProviderDefaultMethods() {
        RoutingChatModelProvider provider = new RoutingChatModelProvider() {
            @Override
            public String providerId() {
                return "test-routing";
            }

            @Override
            public ChatModel create(LlmModelConfig config) {
                return null;
            }
        };

        assertEquals("test-routing", provider.providerId());
        assertTrue(provider.requiresApiKey());
    }
}
