package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;

import java.util.List;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.StructuredOutputMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InfiniteLoopAgentChatModelProviderTest {

    @Mock
    AgentChatModelProvider groqProvider;

    @Mock
    AgentChatModelProvider openrouterProvider;

    @Mock
    ChatModel mockGroqModel;

    @Mock
    ChatModel mockOpenrouterModel;

    InfiniteLoopAgentChatModelProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InfiniteLoopAgentChatModelProvider();
        provider.loopProviders = "groq,openrouter,infinite-loop,missing-provider";
        provider.cooldownBaseSeconds = 30;
        provider.cooldownMaxSeconds = 300;
        provider.cooldownTransientSeconds = 5;
        provider.maxLatencyMs = 60000;

        when(groqProvider.providerName()).thenReturn("groq");
        when(groqProvider.create()).thenReturn(mockGroqModel);
        when(groqProvider.baseUrl()).thenReturn("https://api.groq.com");

        when(openrouterProvider.providerName()).thenReturn("openrouter");
        when(openrouterProvider.create()).thenReturn(mockOpenrouterModel);
        when(openrouterProvider.baseUrl()).thenReturn("https://openrouter.ai");

        @SuppressWarnings("unchecked")
        Instance<AgentChatModelProvider> allProviders = mock(Instance.class);
        when(allProviders.iterator())
                .thenReturn(List.of(groqProvider, openrouterProvider).iterator());
        provider.allProviders = allProviders;
    }

    @Test
    void testMetadata() {
        assertEquals("infinite-loop", provider.providerName());
        assertEquals(StructuredOutputMode.PROMPT_DIRECTIVE, provider.structuredOutputMode());
    }

    @Test
    void testCreateWithValidSlots() {
        ChatModel model = provider.create();
        assertNotNull(model);
    }

    @Test
    void testCreateWithNoValidSlotsThrows() {
        provider.loopProviders = "unknown1,unknown2";
        assertThrows(IllegalStateException.class, () -> provider.create());
    }
}
