package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;

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

    @Mock
    ModelProfileResolver profileResolver;

    @Mock
    ModelProfileCredentialResolver credentialResolver;

    @Mock
    LlmProfileStorePort profileStore;

    @Mock
    AgentLlmConfig agentConfig;

    @Mock
    ChatModelCreationPort chatModelCreationPort;

    ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter();
        router.modelPool = modelPool;
        router.groupRegistry = groupRegistry;
        router.globalProvider = "groq";
        router.globalModel = Optional.of("llama-3.3-70b");
        router.profileResolver = profileResolver;
        router.credentialResolver = credentialResolver;
        router.profileStore = profileStore;
        BeanManager mockBeanManager = mock(BeanManager.class);
        @SuppressWarnings("unchecked")
        Bean<ChatModelCreationPort> mockBean = mock(Bean.class);
        when(mockBean.getBeanClass()).thenReturn((Class) ChatModelCreationPort.class);
        when(mockBean.getTypes()).thenReturn(Set.of(ChatModelCreationPort.class));
        when(mockBeanManager.getBeans(Object.class)).thenReturn(Set.of(mockBean));
        when(mockBeanManager.resolve(any())).thenAnswer(inv -> mockBean);
        when(mockBeanManager.getReference(eq(mockBean), eq(ChatModelCreationPort.class), any()))
                .thenReturn(chatModelCreationPort);
        router.beanManager = mockBeanManager;
        router.profileRoutingEnabled = false;

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
        when(modelPool.getOrCreate(anyString(), any())).thenReturn(groqModel);

        ChatModel result = router.resolve("coder");

        assertSame(groqModel, result);
    }

    @Test
    void resolve_configs_returnsEmptyListForNoConfig() {
        List<ModelRouter.ModelConfig> configs = router.resolveConfigs("coder");

        assertNotNull(configs);
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
        ModelRouter.ModelConfig model = new ModelRouter.ModelConfig("groq", "llama-3.3-70b");
        String str = model.toString();

        assertTrue(str.contains("groq"));
        assertTrue(str.contains("llama-3.3-70b"));
    }

    @Test
    void resolve_profileRoutingDisabled_usesLegacyGlobalFallback() {
        router.profileRoutingEnabled = false;
        when(modelPool.getOrCreate(anyString(), any())).thenReturn(groqModel);

        ChatModel result = router.resolve("coder");

        verifyNoInteractions(profileResolver);
        assertSame(groqModel, result);
    }

    @Test
    void resolve_profileRoutingEnabled_noEligibleProfile_fallsBackToLegacy() {
        router.profileRoutingEnabled = true;
        when(profileResolver.resolve("coder")).thenReturn(RouteResolution.empty("none"));
        when(modelPool.getOrCreate(anyString(), any())).thenReturn(groqModel);

        ChatModel result = router.resolve("coder");

        verify(profileResolver).resolve("coder");
        assertSame(groqModel, result);
    }

    @Test
    void resolve_profileRoutingEnabled_eligibleProfile_buildsFailoverChain() {
        router.profileRoutingEnabled = true;
        ResolvedProfile profile =
                new ResolvedProfile("prof-1", "conn-1", "gpt-4o", "openai", "USER", "u-1", 1, 1, true);
        OrderedFailoverChatModel failover = mock(OrderedFailoverChatModel.class);
        LlmConnectionDto conn = mock(LlmConnectionDto.class);
        when(profileResolver.resolve("coder")).thenReturn(RouteResolution.of("tier-1", List.of(profile)));
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));
        when(credentialResolver.resolve(any(), any()))
                .thenReturn(Optional.of(new ResolvedCredential(
                        "openai", "https://api.openai.com/v1/", "sk-test".getBytes(StandardCharsets.UTF_8))));
        when(modelPool.getOrCreateProfileRoute(anyString(), any())).thenReturn(failover);

        ChatModel result = router.resolve("coder");

        assertSame(failover, result);
        verify(credentialResolver).resolve(any(), any());
        verify(modelPool).getOrCreateProfileRoute(eq("tier-1:USER:u-1:coder"), any());
    }

    @Test
    void resolve_profileRoutingEnabled_eligibleRouteShadowsLightweightShortcut() {
        router.profileRoutingEnabled = true;
        ResolvedProfile profile =
                new ResolvedProfile("prof-1", "conn-1", "gpt-4o", "openai", "USER", "u-1", 1, 1, true);
        OrderedFailoverChatModel failover = mock(OrderedFailoverChatModel.class);
        LlmConnectionDto conn = mock(LlmConnectionDto.class);
        when(profileResolver.resolve("coder")).thenReturn(RouteResolution.of("tier-1", List.of(profile)));
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));
        when(credentialResolver.resolve(any(), any()))
                .thenReturn(Optional.of(new ResolvedCredential(
                        "openai", "https://api.openai.com/v1/", "sk-test".getBytes(StandardCharsets.UTF_8))));
        when(modelPool.getOrCreateProfileRoute(anyString(), any())).thenReturn(failover);

        ChatModel result = router.resolve("coder");

        assertSame(failover, result);
        verify(modelPool, never()).getOrCreate(eq("groq#llama-3.3"), any());
        verify(modelPool).getOrCreateProfileRoute(eq("tier-1:USER:u-1:coder"), any());
    }

    @Test
    void resolve_unknownProvider_throwsModelRoutingException() {
        router.globalProvider = "unknown_provider";
        when(modelPool.getOrCreate(anyString(), any())).thenAnswer(inv -> {
            var supplier = inv.getArgument(1, Supplier.class);
            return supplier.get();
        });

        assertThrows(ModelRoutingException.class, () -> router.resolve("coder"));
    }

    @Test
    void resolve_profileRoutingEnabled_noConnection_fallsBackToLegacy() {
        router.profileRoutingEnabled = true;
        ResolvedProfile profile =
                new ResolvedProfile("prof-1", "conn-missing", "gpt-4o", "openai", "USER", "u-1", 1, 1, true);
        when(profileResolver.resolve("coder")).thenReturn(RouteResolution.of("tier-1", List.of(profile)));
        when(profileStore.findConnection("conn-missing")).thenReturn(Optional.empty());
        when(modelPool.getOrCreate(anyString(), any())).thenReturn(groqModel);

        ChatModel result = router.resolve("coder");

        assertSame(groqModel, result);
    }

    @Test
    void resolve_profileRoutingEnabled_noCredential_fallsBackToLegacy() {
        router.profileRoutingEnabled = true;
        ResolvedProfile profile =
                new ResolvedProfile("prof-1", "conn-1", "gpt-4o", "openai", "USER", "u-1", 1, 1, true);
        LlmConnectionDto conn = mock(LlmConnectionDto.class);
        when(profileResolver.resolve("coder")).thenReturn(RouteResolution.of("tier-1", List.of(profile)));
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));
        when(credentialResolver.resolve(any(), any())).thenReturn(Optional.empty());
        when(modelPool.getOrCreate(anyString(), any())).thenReturn(groqModel);

        ChatModel result = router.resolve("coder");

        assertSame(groqModel, result);
    }
}
