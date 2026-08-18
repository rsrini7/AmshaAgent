package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.core.routing.ClaudeCodeTokenResolver;
import com.amsha.core.routing.CodexTokenResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class AgentProviderFactoryCoverageTest {

    static Stream<Class<? extends AgentChatModelProvider>> providerTypes() {
        return Stream.of(
                CerebrasProviderFactory.class,
                ClaudeCodeProviderFactory.class,
                GeminiProviderFactory.class,
                GenericOpenAiProviderFactory.class,
                GroqProviderFactory.class,
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
