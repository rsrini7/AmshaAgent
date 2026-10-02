package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.MdcKeys;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Branch/edge coverage for {@link ModelRouter}: SPI creation-port resolution, the empty-config
 * global fallback, the numbered and unnumbered persona/group config ladders, multi-model failover
 * wrapping, provider lookup, and the lightweight-goal classifier handoff.
 *
 * <p>Complements {@link ModelRouterTest}, which covers the profile-routing chain and the global
 * fallback. The persona/group ladders read from the thread context classloader's SmallRye config,
 * whose default factory includes the system-property source — so {@link #withProperties} is the
 * seam that drives those branches without a container.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelRouterBranchTest {

    @Mock
    ChatModelPool modelPool;

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

    @Mock
    TaskClassifierService classifierService;

    /** A real registry, so {@code findByMember} resolves the built-in Lokas. */
    final AgentGroupRegistry groupRegistry = new AgentGroupRegistry();

    ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter();
        router.modelPool = modelPool;
        router.groupRegistry = groupRegistry;
        router.classifierService = classifierService;
        router.globalProvider = "groq";
        router.globalModel = Optional.of("llama-3.3-70b");
        router.profileResolver = profileResolver;
        router.credentialResolver = credentialResolver;
        router.profileStore = profileStore;
        router.agentConfig = agentConfig;
        router.beanManager = beanManagerReturning(chatModelCreationPort);
        router.profileRoutingEnabled = false;
        router.providers = providersInstance(groqProvider, openaiProvider);

        when(groqProvider.providerName()).thenReturn("groq");
        when(openaiProvider.providerName()).thenReturn("openai");
        when(groqProvider.create()).thenReturn(groqModel);
        when(groqProvider.create(anyString())).thenReturn(groqModel);
        when(openaiProvider.create()).thenReturn(openaiModel);
        when(openaiProvider.create(anyString())).thenReturn(openaiModel);
        when(classifierService.classify(anyString())).thenReturn(List.of());
    }

    // ── SPI creation port ───────────────────────────────────────────────────

    @Test
    void creationPortReturnsTheResolvedSpiBean() throws Exception {
        Method m = ModelRouter.class.getDeclaredMethod("creationPort");
        m.setAccessible(true);

        assertSame(chatModelCreationPort, m.invoke(router));
    }

    @Test
    void creationPortFailsClosedWhenNoSpiBeanIsPresent() throws Exception {
        router.beanManager = beanManagerWithNoBeans();
        Method m = ModelRouter.class.getDeclaredMethod("creationPort");
        m.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> m.invoke(router));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertTrue(
                thrown.getCause().getMessage().contains("No ChatModelCreationPort bean found"),
                "the failure must name the missing SPI so operators know which module is absent");
    }

    @Test
    void resolveFallsBackToTheGlobalProviderWhenConfigResolutionYieldsNothing() {
        ModelRouter noConfigRouter = new ModelRouter() {
            @Override
            public List<ModelConfig> resolveConfigs(String agentRole, boolean bypassLightweightDowngrade) {
                return List.of();
            }
        };
        noConfigRouter.modelPool = modelPool;
        noConfigRouter.globalProvider = "groq";
        noConfigRouter.globalModel = Optional.of("llama-3.3-70b");
        when(modelPool.getOrCreate(anyString(), any())).thenReturn(groqModel);

        assertSame(groqModel, noConfigRouter.resolve("coder"));

        verify(modelPool).getOrCreate(eq("groq#llama-3.3-70b"), any());
    }

    @Test
    void resolveProfileRoutedBuildsTheAdapterThroughTheSpiCreationPort() {
        router.profileRoutingEnabled = true;
        ResolvedProfile profile =
                new ResolvedProfile("prof-1", "conn-1", "gpt-4o", "openai", "USER", "u-1", 1, 1, true);
        when(profileResolver.resolve("coder")).thenReturn(RouteResolution.of("tier-1", List.of(profile)));
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(mock(LlmConnectionDto.class)));
        when(credentialResolver.resolve(any(), any()))
                .thenReturn(Optional.of(new ResolvedCredential(
                        "openai", "https://api.openai.com/v1/", "sk-test".getBytes(StandardCharsets.UTF_8))));
        when(chatModelCreationPort.createForAdapter(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(openaiModel);
        when(modelPool.getOrCreateTyped(any(), any(), any(), anyInt(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(
                        invocation -> invocation.getArgument(8, Supplier.class).get());
        // Run the real supplier, so the test proves the route wrapper is actually constructed and
        // not merely that the pool was asked for one.
        when(modelPool.getOrCreateProfileRoute(anyString(), any()))
                .thenAnswer(
                        invocation -> invocation.getArgument(1, Supplier.class).get());

        ChatModel result = router.resolve("coder");

        assertInstanceOf(OrderedFailoverChatModel.class, result);

        verify(chatModelCreationPort)
                .createForAdapter("openai", "https://api.openai.com/v1/", "sk-test", "gpt-4o", agentConfig);
        verify(modelPool).getOrCreateProfileRoute(eq("tier-1:USER:u-1:coder"), any());
    }

    // ── Persona / group config ladders ──────────────────────────────────────

    @Test
    void numberedPersonaEntriesBuildAMultiModelFailoverChain() throws Exception {
        when(modelPool.getOrCreate(anyString(), any()))
                .thenAnswer(
                        invocation -> invocation.getArgument(1, Supplier.class).get());

        ChatModel result = withProperties(
                Map.of(
                        "amsha.llm.persona.models.ganesh.0.provider", "groq",
                        "amsha.llm.persona.models.ganesh.0.model", "llama-3.3-70b",
                        "amsha.llm.persona.models.ganesh.1.provider", "openai",
                        "amsha.llm.persona.models.ganesh.1.model", "gpt-4o"),
                () -> router.resolve("ganesh"));

        assertInstanceOf(MultiModelChatModel.class, result);
        verify(modelPool).getOrCreate(eq("groq#llama-3.3-70b,openai#gpt-4o"), any());
        verify(groqProvider).create("llama-3.3-70b");
        verify(openaiProvider).create("gpt-4o");
    }

    @Test
    void aliasKeyResolvesAnUnnumberedPersonaConfig() throws Exception {
        List<ModelRouter.ModelConfig> configs = withProperties(
                Map.of(
                        "amsha.llm.persona.models.brahaspati.provider", "openai",
                        "amsha.llm.persona.models.brahaspati.model", "gpt-4o"),
                () -> router.resolveConfigs("brihaspati"));

        assertEquals(List.of(new ModelRouter.ModelConfig("openai", "gpt-4o")), configs);
    }

    @Test
    void numberedGroupEntriesBuildAMultiModelFailoverChain() throws Exception {
        List<ModelRouter.ModelConfig> configs = withProperties(
                Map.of(
                        "amsha.llm.group.models.brahmaloka.0.provider", "groq",
                        "amsha.llm.group.models.brahmaloka.0.model", "llama-3.3-70b",
                        "amsha.llm.group.models.brahmaloka.1.provider", "openai",
                        "amsha.llm.group.models.brahmaloka.1.model", "gpt-4o"),
                () -> router.resolveConfigs("coder"));

        assertEquals(
                List.of(
                        new ModelRouter.ModelConfig("groq", "llama-3.3-70b"),
                        new ModelRouter.ModelConfig("openai", "gpt-4o")),
                configs);
    }

    @Test
    void unnumberedGroupConfigIsUsedWhenNoNumberedEntriesExist() throws Exception {
        List<ModelRouter.ModelConfig> configs = withProperties(
                Map.of(
                        "amsha.llm.group.models.kailasa.provider", "openai",
                        "amsha.llm.group.models.kailasa.model", "gpt-4o"),
                () -> router.resolveConfigs("debugger"));

        assertEquals(List.of(new ModelRouter.ModelConfig("openai", "gpt-4o")), configs);
    }

    // ── Provider default ────────────────────────────────────────────────────

    @Test
    void globalFallbackWithoutAConfiguredModelUsesTheProviderDefault() {
        router.globalModel = Optional.empty();
        when(modelPool.getOrCreate(anyString(), any()))
                .thenAnswer(
                        invocation -> invocation.getArgument(1, Supplier.class).get());

        assertSame(groqModel, router.resolve("coder"));

        verify(modelPool).getOrCreate(eq("groq#"), any());
        verify(groqProvider).create();
        verify(groqProvider, never()).create(anyString());
    }

    // ── Lightweight-goal classifier handoff ─────────────────────────────────

    @Test
    void lightweightClassificationDefersToTheTaskClassifier() throws Exception {
        Method m = ModelRouter.class.getDeclaredMethod("isLightweightTask", String.class);
        m.setAccessible(true);

        when(classifierService.classify("please refactor the module"))
                .thenReturn(List.of(TaskClassifierService.TaskDomain.CODING));
        assertFalse(
                (Boolean) m.invoke(router, "please refactor the module"),
                "a coding domain must never be downgraded to the low-cost provider");

        when(classifierService.classify("summarize the quarterly report"))
                .thenReturn(List.of(TaskClassifierService.TaskDomain.RESEARCH));
        assertFalse(
                (Boolean) m.invoke(router, "summarize the quarterly report"),
                "a long goal with light domains still exceeds the cheap-model budget");

        when(classifierService.classify("summarize the quarterly report")).thenReturn(List.of());
        assertTrue(
                (Boolean) m.invoke(router, "summarize the quarterly report"),
                "an unclassifiable goal falls back to the lightweight path");
    }

    // ── Escalation tier and actor identity ──────────────────────────────────

    @Test
    void hasDistinctEscalationTierReportsAGenuineHigherTier() throws Exception {
        boolean distinct = withProperties(
                Map.of(
                        "amsha.llm.persona.models.ganesh.provider", "openai",
                        "amsha.llm.persona.models.ganesh.model", "gpt-4o"),
                () -> ScopedValue.where(WorkspaceContext.RAW_USER_GOAL, "hi")
                        .call(() -> router.hasDistinctEscalationTier("ganesh")));

        assertTrue(
                distinct,
                "a lightweight downgrade to groq must be reported as distinct from the persona's openai route");
    }

    @Test
    void currentUserIdFallsBackToTheMdcWhenNoScopedValueIsBound() throws Exception {
        Method m = ModelRouter.class.getDeclaredMethod("currentUserId");
        m.setAccessible(true);
        MDC.put(MdcKeys.USER_ID, "mdc-actor");
        try {
            assertEquals("mdc-actor", m.invoke(router));
        } finally {
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Runs {@code action} with the given keys installed as system properties and restores the
     * previous values afterwards. The thread context classloader's SmallRye config reads the
     * system-property source live, so this is the seam that drives the persona/group ladders.
     */
    private <T> T withProperties(Map<String, String> properties, Callable<T> action) throws Exception {
        Map<String, String> previous = new HashMap<>();
        properties.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
        try {
            properties.forEach(System::setProperty);
            return action.call();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    private static BeanManager beanManagerReturning(ChatModelCreationPort port) {
        BeanManager beanManager = mock(BeanManager.class);
        @SuppressWarnings("unchecked")
        Bean<ChatModelCreationPort> bean = mock(Bean.class);
        when(bean.getBeanClass()).thenReturn((Class) ChatModelCreationPort.class);
        when(bean.getTypes()).thenReturn(Set.of(ChatModelCreationPort.class));
        when(beanManager.getBeans(Object.class)).thenReturn(Set.of(bean));
        when(beanManager.resolve(any())).thenAnswer(invocation -> bean);
        when(beanManager.getReference(eq(bean), eq(ChatModelCreationPort.class), any()))
                .thenReturn(port);
        return beanManager;
    }

    private static BeanManager beanManagerWithNoBeans() {
        BeanManager beanManager = mock(BeanManager.class);
        when(beanManager.getBeans(Object.class)).thenReturn(Set.of());
        return beanManager;
    }

    @SuppressWarnings("unchecked")
    private static Instance<AgentChatModelProvider> providersInstance(AgentChatModelProvider... available) {
        Instance<AgentChatModelProvider> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(available).iterator());
        return instance;
    }
}
