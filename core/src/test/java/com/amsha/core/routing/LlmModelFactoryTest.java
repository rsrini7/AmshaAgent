package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;

import java.util.List;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.RoutingChatModelProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmModelFactoryTest {

    private LlmModelFactory factory;
    private RoutingChatModelProvider groqProvider;
    private ChatModel groqModel;

    @BeforeEach
    void setUp() {
        factory = new LlmModelFactory();
        groqProvider = mock(RoutingChatModelProvider.class);
        groqModel = mock(ChatModel.class);
        when(groqProvider.providerId()).thenReturn("groq");
        when(groqProvider.create(any())).thenReturn(groqModel);

        @SuppressWarnings("unchecked")
        Instance<RoutingChatModelProvider> instance = mock(Instance.class);
        when(instance.stream()).thenReturn(List.of(groqProvider).stream());
        factory.factories = instance;
    }

    @Test
    void testInferProviderFromUrl() {
        assertEquals("groq", LlmModelFactory.inferProviderFromUrl("https://api.groq.com/openai/v1"));
        assertEquals("openai", LlmModelFactory.inferProviderFromUrl("https://api.openai.com/v1"));
        assertEquals("nvidia", LlmModelFactory.inferProviderFromUrl("https://integrate.api.nvidia.com/v1"));
        assertEquals("cerebras", LlmModelFactory.inferProviderFromUrl("https://api.cerebras.ai/v1"));
        assertEquals("sarvam", LlmModelFactory.inferProviderFromUrl("https://api.sarvam.ai/v1"));
        assertEquals("opencode", LlmModelFactory.inferProviderFromUrl("https://opencode.ai/zen/v1"));
        assertEquals("huggingface", LlmModelFactory.inferProviderFromUrl("https://router.huggingface.co/v1"));
        assertEquals("mistral", LlmModelFactory.inferProviderFromUrl("https://api.mistral.ai/v1"));
        assertEquals(
                "cloudflare",
                LlmModelFactory.inferProviderFromUrl("https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1"));
        assertEquals("cohere", LlmModelFactory.inferProviderFromUrl("https://api.cohere.ai/compatibility/v1"));
        assertEquals(
                "gemini",
                LlmModelFactory.inferProviderFromUrl("https://generativelanguage.googleapis.com/v1beta/openai/"));
        assertEquals("ollama", LlmModelFactory.inferProviderFromUrl("http://localhost:11434"));
        assertEquals("openrouter", LlmModelFactory.inferProviderFromUrl("https://openrouter.ai/api/v1"));
        assertEquals("openrouter", LlmModelFactory.inferProviderFromUrl(null));
        assertEquals("openrouter", LlmModelFactory.inferProviderFromUrl(""));
    }

    @Test
    void testCreateModel_delegatesToRegisteredProvider() {
        ChatModel model = factory.createModel(
                "groq", "https://api.groq.com/openai/v1", "test-key", "llama3-8b", 100, null, 1, null, null);
        assertNotNull(model);
        assertEquals(groqModel, model);
    }

    @Test
    void testCreateModel_unregisteredProvider_throwsException() {
        assertThrows(IllegalStateException.class, () -> {
            factory.createModel(
                    "unregistered", "https://api.unregistered.com/v1", "key", "model", 100, null, 1, null, null);
        });
    }

    @Test
    void testCreateModel_capabilityDerivesFromResolvedProvider_notGlobalConstant() {
        RoutingChatModelProvider advertising = mock(RoutingChatModelProvider.class);
        when(advertising.providerId()).thenReturn("openai");
        when(advertising.supportsStrictJsonSchema()).thenReturn(true);

        @SuppressWarnings("unchecked")
        Instance<RoutingChatModelProvider> instance = mock(Instance.class);
        when(instance.stream()).thenAnswer(inv -> List.of(groqProvider, advertising).stream());
        LlmModelFactory localFactory = new LlmModelFactory(instance);

        LlmModelConfig[] captured = new LlmModelConfig[1];
        when(advertising.create(any())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(0, LlmModelConfig.class);
            return groqModel;
        });
        when(groqProvider.create(any())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(0, LlmModelConfig.class);
            return groqModel;
        });

        localFactory.createModel("openai", "https://api.openai.com/v1", "key", "gpt-4o", 100, null, 1, null, null);
        assertNotNull(captured[0]);
        assertTrue(captured[0].strictJsonSchemaAdvertised(), "advertising provider must carry advertisement");

        localFactory.createModel(
                "groq", "https://api.groq.com/openai/v1", "key", "llama3-8b", 100, null, 1, null, null);
        assertNotNull(captured[0]);
        assertFalse(captured[0].strictJsonSchemaAdvertised(), "fail-closed default must stay false");
    }
}
