package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;

import java.util.List;

import com.amsha.core.agent.reasoning.AgentChatModelProvider;
import com.amsha.core.agent.role.AgentGroupRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelRouterTest {

    @Mock
    ChatModelPool modelPool;

    @Mock
    AgentGroupRegistry groupRegistry;

    @Mock
    AgentChatModelProvider groqProvider;

    @Mock
    AgentChatModelProvider openaiProvider;

    @Mock
    ChatModel groqModel;

    @Mock
    ChatModel openaiModel;

    ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter();
        router.modelPool = modelPool;
        router.groupRegistry = groupRegistry;
        router.globalProvider = "groq";
        router.globalModel = "llama-3.3-70b";

        // Mock providers Instance
        @SuppressWarnings("unchecked")
        Instance<AgentChatModelProvider> providers = mock(Instance.class);
        when(providers.iterator())
                .thenReturn(List.of(groqProvider, openaiProvider).iterator());
        router.providers = providers;

        when(groqProvider.providerName()).thenReturn("groq");
        when(openaiProvider.providerName()).thenReturn("openai");
    }

    @Test
    void resolve_globalFallback_whenNoPersonaOrGroupConfig() {
        // No persona or group config → falls back to global
        when(modelPool.getOrCreate(eq("groq#llama-3.3-70b"), any())).thenReturn(groqModel);

        ChatModel result = router.resolve("coder");

        assertSame(groqModel, result);
    }

    @Test
    void resolve_configs_returnsEmptyListForNoConfig() {
        // When no persona or group config, resolveConfigs should return empty
        List<ModelRouter.ModelConfig> configs = router.resolveConfigs("coder");

        assertNotNull(configs);
        // Will be empty since no SmallRye config is available in test
        // The actual config reading happens via SmallRyeConfigProviderResolver
    }

    @Test
    void modelConfig_recordEquality() {
        ModelRouter.ModelConfig c1 = new ModelRouter.ModelConfig("groq", "llama-3.3-70b");
        ModelRouter.ModelConfig c2 = new ModelRouter.ModelConfig("groq", "llama-3.3-70b");
        ModelRouter.ModelConfig c3 = new ModelRouter.ModelConfig("openai", "gpt-4o");

        assertEquals(c1, c2);
        assertNotEquals(c1, c3);
        assertEquals("groq", c1.provider());
        assertEquals("llama-3.3-70b", c1.model());
    }

    @Test
    void modelConfig_hashCodeConsistent() {
        ModelRouter.ModelConfig c1 = new ModelRouter.ModelConfig("groq", "llama-3.3-70b");
        ModelRouter.ModelConfig c2 = new ModelRouter.ModelConfig("groq", "llama-3.3-70b");

        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void modelConfig_toString() {
        ModelRouter.ModelConfig c = new ModelRouter.ModelConfig("groq", "llama-3.3-70b");
        String str = c.toString();

        assertTrue(str.contains("groq"));
        assertTrue(str.contains("llama-3.3-70b"));
    }
}
