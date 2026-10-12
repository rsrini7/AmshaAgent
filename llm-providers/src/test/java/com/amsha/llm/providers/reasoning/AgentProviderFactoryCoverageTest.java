package com.amsha.llm.providers.reasoning;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.helper.AgyTokenResolver;
import com.amsha.llm.providers.helper.ClaudeCodeTokenResolver;
import com.amsha.llm.providers.helper.CodexTokenResolver;
import com.amsha.llm.providers.helper.KimchiTokenResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class AgentProviderFactoryCoverageTest {

    static Stream<Class<? extends AgentChatModelProvider>> providerTypes() {
        return Stream.of(
                AgyProviderFactory.class,
                CerebrasProviderFactory.class,
                ClaudeCodeProviderFactory.class,
                FoundryProviderFactory.class,
                GeminiProviderFactory.class,
                GenericOpenAiProviderFactory.class,
                GroqProviderFactory.class,
                KimchiProviderFactory.class,
                LlamaCppProviderFactory.class,
                NvidiaProviderFactory.class,
                OllamaProviderFactory.class,
                OmlxProviderFactory.class,
                OpenAiProviderFactory.class,
                OpenRouterProviderFactory.class,
                SarvamProviderFactory.class,
                OpenCodeProviderFactory.class,
                HuggingFaceProviderFactory.class,
                MistralProviderFactory.class,
                CloudflareProviderFactory.class,
                CohereProviderFactory.class,
                CodexProviderFactory.class);
    }

    @ParameterizedTest
    @MethodSource("providerTypes")
    void exposesMetadataAndExercisesConfigurationValidation(Class<? extends AgentChatModelProvider> type)
            throws Exception {
        AgentChatModelProvider provider = type.getDeclaredConstructor().newInstance();
        initializeFields(provider);

        assertAll(
                () -> assertFalse(provider.providerName().isBlank()),
                () -> assertNotNull(provider.structuredOutputMode()),
                () -> assertDoesNotThrow(provider::baseUrl));

        try {
            assertNotNull(provider.create());
        } catch (IllegalStateException expectedForMissingCredentials) {
            assertFalse(expectedForMissingCredentials.getMessage().isBlank());
        }
        try {
            assertNotNull(provider.create("test-model"));
        } catch (IllegalStateException expectedForMissingCredentials) {
            assertFalse(expectedForMissingCredentials.getMessage().isBlank());
        }
    }

    @Test
    void agentConfigAccessorsReturnConfiguredValues() throws Exception {
        AgentLlmConfig config = new AgentLlmConfig();
        set(config, "maxTokens", 321);
        set(config, "timeout", Duration.ofSeconds(9));
        set(config, "maxRetries", 4);
        set(config, "temperature", 0.25);
        set(config, "logHttp", true);
        set(config, "structuredOutputMode", StructuredOutputMode.NATIVE);

        assertAll(
                () -> assertEquals(321, config.maxTokens()),
                () -> assertEquals(Duration.ofSeconds(9), config.timeout()),
                () -> assertEquals(4, config.maxRetries()),
                () -> assertEquals(0.25, config.temperature()),
                () -> assertTrue(config.logHttp()),
                () -> assertNotNull(config.structuredOutputMode()));
    }

    @Test
    void geminiProviderFactoryBuildsModelWithValidCredentials() throws Exception {
        GeminiProviderFactory factory = new GeminiProviderFactory();
        initializeFields(factory);
        set(factory, "apiKey", Optional.of("test-gemini-key"));
        set(factory, "baseUrl", "https://generativelanguage.googleapis.com/v1beta");
        set(factory, "modelName", "gemini-2.5-flash");

        assertNotNull(factory.create());
        assertNotNull(factory.create("gemini-1.5-pro"));
    }

    // ---------------------------------------------------------------------------------------------
    // Cloudflare resolves its base URL from an account ID, because the Workers AI endpoint embeds the
    // account in the path. This is pure string logic and needs no CDI container.
    // ---------------------------------------------------------------------------------------------

    @Test
    void cloudflareSubstitutesTheAccountIdIntoTheBaseUrl() throws Exception {
        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);
        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1");
        set(factory, "accountId", Optional.of("acc-123"));

        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc-123/ai/v1", factory.resolveEffectiveBaseUrl());
    }

    @Test
    void cloudflareSanitizesAnAccountIdCopiedFromTheDashboardUrl() throws Exception {
        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);
        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1");
        set(factory, "accountId", Optional.of("accounts/acc-123/ai/v1"));

        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc-123/ai/v1", factory.resolveEffectiveBaseUrl());
    }

    @Test
    void cloudflareFallsBackToSystemPropertiesForTheAccountId() throws Exception {
        String property = ConfigKeys.CLOUDFLARE_ACCOUNT_ID;
        String previous = System.getProperty(property);
        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);
        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1");
        set(factory, "accountId", Optional.empty());

        try {
            System.setProperty(property, "prop-acc");
            assertEquals(
                    "https://api.cloudflare.com/client/v4/accounts/prop-acc/ai/v1", factory.resolveEffectiveBaseUrl());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void cloudflareRejectsAnUnresolvableAccountId() throws Exception {
        // The ladder ends in System.getenv, which is not settable from a test; skip rather than assert
        // against whatever the ambient environment happens to hold.
        assumeTrue(System.getenv(ConfigKeys.CLOUDFLARE_ACCOUNT_ID) == null, "env account ID is set on this machine");

        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);
        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1");
        set(factory, "accountId", Optional.empty());

        assertThrows(IllegalStateException.class, factory::resolveEffectiveBaseUrl);
    }

    @Test
    void cloudflareRejectsABaseUrlThatStaysMalformedAfterSubstitution() throws Exception {
        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);
        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/{account_id}//ai/v1");
        set(factory, "accountId", Optional.of("acc-123"));

        assertThrows(IllegalStateException.class, factory::resolveEffectiveBaseUrl);
    }

    @Test
    void cloudflareNormalizesATrailingBraceAndDuplicatedPathSegments() throws Exception {
        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);

        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/acc/ai/v1}");
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc/ai/v1", factory.resolveEffectiveBaseUrl());

        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/acc/ai/v1/ai/v1");
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc/ai/v1", factory.resolveEffectiveBaseUrl());

        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/accounts/acc/v1/v1");
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc/v1", factory.resolveEffectiveBaseUrl());
    }

    @Test
    void cloudflareRejectsAnIllegalBraceInTheMiddleOfTheUrl() throws Exception {
        CloudflareProviderFactory factory = new CloudflareProviderFactory();
        initializeFields(factory);
        set(factory, "baseUrl", "https://api.cloudflare.com/client/v4/v1}corrupt");

        assertThrows(IllegalStateException.class, factory::resolveEffectiveBaseUrl);
        // baseUrl() is the defensive wrapper: a resolution failure degrades to the configured value
        // rather than propagating out of the provider metadata surface.
        assertEquals("https://api.cloudflare.com/client/v4/v1}corrupt", factory.baseUrl());
    }

    // ---------------------------------------------------------------------------------------------
    // The credential-free local providers expose convenience constructors used by callers that build
    // them outside CDI.
    // ---------------------------------------------------------------------------------------------

    @Test
    void ollamaConvenienceConstructorsPopulateTheInjectedFields() {
        AgentLlmConfig config = mock(AgentLlmConfig.class);
        Mockito.when(config.structuredOutputMode()).thenReturn(StructuredOutputMode.NATIVE);

        OllamaProviderFactory threeArg = new OllamaProviderFactory(config, "http://localhost:11434", "llama3");
        OllamaProviderFactory fourArg = new OllamaProviderFactory(config, "http://localhost:11434", "llama3", null);

        assertEquals(ModelDefaults.PROVIDER_OLLAMA, threeArg.providerName());
        assertEquals("http://localhost:11434", threeArg.baseUrl());
        assertEquals("http://localhost:11434", fourArg.baseUrl());
        assertEquals(StructuredOutputMode.NATIVE, fourArg.structuredOutputMode());
    }

    @Test
    void llamaCppConvenienceConstructorsPopulateTheInjectedFields() {
        AgentLlmConfig config = mock(AgentLlmConfig.class);
        Mockito.when(config.structuredOutputMode()).thenReturn(StructuredOutputMode.NATIVE);

        LlamaCppProviderFactory threeArg = new LlamaCppProviderFactory(config, "http://localhost:8080/v1", "local");
        LlamaCppProviderFactory fourArg =
                new LlamaCppProviderFactory(config, "http://localhost:8080/v1", "local", null);

        assertEquals(ModelDefaults.PROVIDER_LLAMACPP, threeArg.providerName());
        assertEquals("http://localhost:8080/v1", threeArg.baseUrl());
        assertEquals("http://localhost:8080/v1", fourArg.baseUrl());
        assertEquals(StructuredOutputMode.NATIVE, fourArg.structuredOutputMode());
    }

    /**
     * The generic OpenAI-compatible provider has three distinct fail-closed arms; the reflective
     * smoke test above only ever reaches the first (a missing base URL), because it leaves every
     * {@code Optional} empty.
     */
    @Test
    void genericOpenAiReportsEachMissingConfigurationValueSpecifically() throws Exception {
        GenericOpenAiProviderFactory factory = new GenericOpenAiProviderFactory();
        initializeFields(factory);

        set(factory, "baseUrl", Optional.of("http://localhost:11434/v1"));
        set(factory, "modelName", Optional.of("test-model"));
        set(factory, "apiKey", Optional.empty());
        assertThrows(IllegalStateException.class, factory::create);

        set(factory, "apiKey", Optional.of("test-api-key"));
        assertThrows(IllegalStateException.class, () -> factory.create(""));
    }

    private static void initializeFields(Object target) throws Exception {
        for (Field field : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType() == AgentLlmConfig.class) {
                AgentLlmConfig config = mock(AgentLlmConfig.class);
                Mockito.when(config.timeout()).thenReturn(Duration.ofSeconds(1));
                Mockito.when(config.structuredOutputMode()).thenReturn(StructuredOutputMode.NATIVE);
                field.set(target, config);
            } else if (field.getType() == Optional.class) {
                field.set(target, Optional.empty());
            } else if (field.getType() == String.class) {
                field.set(target, "http://localhost:11434");
            } else if (field.getType() == CodexTokenResolver.class) {
                CodexTokenResolver resolver = mock(CodexTokenResolver.class);
                Mockito.when(resolver.resolveToken(Mockito.any()))
                        .thenThrow(new IllegalStateException("Missing credentials"));
                Mockito.when(resolver.resolveToken()).thenThrow(new IllegalStateException("Missing credentials"));
                field.set(target, resolver);
            } else if (field.getType() == ClaudeCodeTokenResolver.class) {
                ClaudeCodeTokenResolver resolver = mock(ClaudeCodeTokenResolver.class);
                Mockito.when(resolver.resolveToken(Mockito.any()))
                        .thenThrow(new IllegalStateException("Missing credentials"));
                Mockito.when(resolver.resolveToken()).thenThrow(new IllegalStateException("Missing credentials"));
                field.set(target, resolver);
            } else if (field.getType() == AgyTokenResolver.class) {
                AgyTokenResolver resolver = mock(AgyTokenResolver.class);
                Mockito.when(resolver.resolveToken(Mockito.any()))
                        .thenThrow(new IllegalStateException("Missing credentials"));
                Mockito.when(resolver.resolveToken()).thenThrow(new IllegalStateException("Missing credentials"));
                field.set(target, resolver);
            } else if (field.getType() == KimchiTokenResolver.class) {
                KimchiTokenResolver resolver = mock(KimchiTokenResolver.class);
                Mockito.when(resolver.resolveToken(Mockito.any()))
                        .thenThrow(new IllegalStateException("Missing credentials"));
                Mockito.when(resolver.resolveToken()).thenThrow(new IllegalStateException("Missing credentials"));
                field.set(target, resolver);
            } else if (!field.getType().isPrimitive()) {
                field.set(target, mock(field.getType()));
            }
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
