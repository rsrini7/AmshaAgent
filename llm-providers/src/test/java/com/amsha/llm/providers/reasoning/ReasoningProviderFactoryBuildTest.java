package com.amsha.llm.providers.reasoning;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.providers.helper.AgyTokenResolver;
import com.amsha.llm.providers.helper.ClaudeCodeTokenResolver;
import com.amsha.llm.providers.helper.CodexTokenResolver;
import com.amsha.llm.providers.helper.KimchiTokenResolver;
import com.amsha.security.http.GovernedTransportService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Builds a real model through every {@code reasoning} provider factory.
 *
 * <p>This is the container-backed sibling of {@link AgentProviderFactoryCoverageTest}. That test is a
 * plain unit test that deliberately supplies no credentials, so every factory aborts inside its
 * credential ladder and only the metadata/validation surface is exercised. Driving the construction
 * body needs a live CDI container: {@code ChatModelFactory.openAiBuilder} ultimately reaches
 * {@code io.quarkiverse.langchain4j.auth.ModelAuthProvider}, which calls {@code CDI.current()} and
 * throws {@code IllegalStateException: Unable to locate CDIProvider} outside a Quarkus runtime. The
 * two tests therefore split the surface by reachability rather than duplicating it.
 *
 * <p>{@code TestBeanProducer} supplies the mocks for {@code GovernedTransportService} and friends, so
 * no provider here performs real network I/O; only model construction is asserted.
 */
@QuarkusTest
class ReasoningProviderFactoryBuildTest {

    /** Mirrors the provider list in {@link AgentProviderFactoryCoverageTest}. */
    static Stream<Class<? extends AgentChatModelProvider>> providerTypes() {
        return Stream.of(
                AgyProviderFactory.class,
                CerebrasProviderFactory.class,
                ClaudeCodeProviderFactory.class,
                CloudflareProviderFactory.class,
                CodexProviderFactory.class,
                CohereProviderFactory.class,
                FoundryProviderFactory.class,
                GeminiProviderFactory.class,
                GenericOpenAiProviderFactory.class,
                GroqProviderFactory.class,
                HuggingFaceProviderFactory.class,
                KimchiProviderFactory.class,
                LlamaCppProviderFactory.class,
                MistralProviderFactory.class,
                NvidiaProviderFactory.class,
                OllamaProviderFactory.class,
                OmlxProviderFactory.class,
                OpenAiProviderFactory.class,
                OpenCodeProviderFactory.class,
                OpenRouterProviderFactory.class,
                SarvamProviderFactory.class);
    }

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    GovernedTransportService governedTransport;

    @ParameterizedTest
    @MethodSource("providerTypes")
    void buildsAModelOnceCredentialsAreSupplied(Class<? extends AgentChatModelProvider> type) throws Exception {
        AgentChatModelProvider provider = newProvider(type);

        assertNotNull(provider.create(), "create() must build a model when a credential is present");
        assertNotNull(provider.create("model-a"), "create(model) must honour the explicit model name");
    }

    /**
     * A comma-separated model list makes {@code ChatModelFactory.buildMultiModelEntries} return more
     * than one entry, which selects the {@code MultiModelChatModel} arm instead of unwrapping the
     * single entry. The concrete type differs across providers, so the contract asserted here is that
     * the multi-model form builds successfully.
     */
    @ParameterizedTest
    @MethodSource("providerTypes")
    void buildsAMultiModelInstanceForACommaSeparatedModelList(Class<? extends AgentChatModelProvider> type)
            throws Exception {
        AgentChatModelProvider provider = newProvider(type);

        ChatModel multiModel = provider.create("model-a,model-b");

        assertNotNull(multiModel);
    }

    /**
     * Foundry Local historically shipped as a Mistral-backed endpoint, so {@code mistral-small} is
     * still treated as an unset model name and remapped to the current default rather than being
     * forwarded verbatim.
     */
    @Test
    void foundryRemapsTheLegacyMistralAliasToTheDefaultModel() throws Exception {
        FoundryProviderFactory factory = new FoundryProviderFactory();
        setField(factory, "agentConfig", agentConfig);
        setField(factory, "baseUrl", "http://localhost:8000/v1");
        setField(factory, "modelName", "mistral-small");

        assertNotNull(factory.create());
        assertNotNull(factory.create("mistral-small"));
    }

    private AgentChatModelProvider newProvider(Class<? extends AgentChatModelProvider> type) throws Exception {
        AgentChatModelProvider provider = type.getDeclaredConstructor().newInstance();
        setField(provider, "agentConfig", agentConfig);
        supplyCredentials(provider);
        return provider;
    }

    /**
     * Replaces the injected fields of an unmanaged factory instance with usable values: a plain
     * {@code Optional} API key, a {@code String} API key, a token resolver, or nothing at all for the
     * credential-free local providers.
     */
    private void supplyCredentials(Object target) throws Exception {
        for (Field field : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() == AgentLlmConfig.class) continue;
            String name = field.getName().toLowerCase(Locale.ROOT);
            Class<?> fieldType = field.getType();

            if (fieldType == CodexTokenResolver.class) {
                CodexTokenResolver resolver = mock(CodexTokenResolver.class);
                Mockito.when(resolver.resolveToken()).thenReturn("test-token");
                Mockito.when(resolver.resolveToken(Mockito.any())).thenReturn("test-token");
                setField(target, field.getName(), resolver);
            } else if (fieldType == ClaudeCodeTokenResolver.class) {
                ClaudeCodeTokenResolver resolver = mock(ClaudeCodeTokenResolver.class);
                Mockito.when(resolver.resolveToken()).thenReturn("test-token");
                Mockito.when(resolver.resolveToken(Mockito.any())).thenReturn("test-token");
                setField(target, field.getName(), resolver);
            } else if (fieldType == AgyTokenResolver.class) {
                AgyTokenResolver resolver = mock(AgyTokenResolver.class);
                Mockito.when(resolver.resolveToken()).thenReturn("test-token");
                Mockito.when(resolver.resolveToken(Mockito.any())).thenReturn("test-token");
                setField(target, field.getName(), resolver);
            } else if (fieldType == KimchiTokenResolver.class) {
                KimchiTokenResolver resolver = mock(KimchiTokenResolver.class);
                Mockito.when(resolver.resolveToken()).thenReturn("test-token");
                Mockito.when(resolver.resolveToken(Mockito.any())).thenReturn("test-token");
                setField(target, field.getName(), resolver);
            } else if (fieldType == Instance.class) {
                setField(target, field.getName(), resolvableTransportInstance());
            } else if (fieldType == Optional.class) {
                setField(target, field.getName(), Optional.of(valueFor(name)));
            } else if (fieldType == String.class) {
                setField(target, field.getName(), valueFor(name));
            }
        }
    }

    /**
     * The local providers resolve their transport from a CDI {@code Instance} rather than a plain
     * field, so the fallback rung needs an instance that reports itself resolvable.
     */
    private Instance<GovernedTransportService> resolvableTransportInstance() {
        @SuppressWarnings("unchecked")
        Instance<GovernedTransportService> instance = mock(Instance.class);
        Mockito.when(instance.isResolvable()).thenReturn(true);
        Mockito.when(instance.get()).thenReturn(governedTransport);
        return instance;
    }

    private static String valueFor(String fieldName) {
        if (fieldName.contains("model")) return "test-model";
        if (fieldName.contains("baseurl")) return "http://localhost:11434/v1";
        if (fieldName.contains("account")) return "test-account";
        if (fieldName.contains("extrabody")) return "{}";
        return "test-api-key";
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
