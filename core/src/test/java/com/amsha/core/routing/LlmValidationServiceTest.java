package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import com.amsha.core.llm.LlmValidationException;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmValidationServiceTest {

    @Mock
    SecretVault vault;

    @Mock
    LlmModelFactory modelFactory;

    @Mock
    ChatModel chatModel;

    @Mock
    Executor validationExecutor;

    @InjectMocks
    LlmValidationService service;

    @BeforeEach
    void setUp() {
        // Execute supplyAsync tasks synchronously on the calling thread
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(validationExecutor)
                .execute(any(Runnable.class));
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate stores API key and returns true on successful chat")
    void validateReturnsTrueOnSuccess() throws LlmValidationException {
        String apiKey = "sk-test-123";
        when(modelFactory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(Duration.class),
                        anyInt(),
                        any(),
                        any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenReturn("Hello!");

        boolean result = service.validate("openai", "gpt-4o-mini", apiKey);

        assertTrue(result);
        verify(vault).storeSystemKey(VaultConstants.LLM_API_KEY, apiKey.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("validate skips vault store for claude-code provider")
    void validateSkipsVaultForClaudeCode() throws LlmValidationException {
        when(modelFactory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(Duration.class),
                        anyInt(),
                        any(),
                        any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenReturn("Hey there!");

        boolean result = service.validate("claude-code", "claude-sonnet-4-20250514", "sk-cc-key");

        assertTrue(result);
        verify(vault, never()).storeSystemKey(anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("validate skips vault store for agy provider")
    void validateSkipsVaultForAgy() throws LlmValidationException {
        when(modelFactory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(Duration.class),
                        anyInt(),
                        any(),
                        any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenReturn("Hey there!");

        boolean result = service.validate("agy", "gemini-3.1-flash-lite", "sk-agy-key");

        assertTrue(result);
        verify(vault, never()).storeSystemKey(anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("validate skips vault store for foundry provider")
    void validateSkipsVaultForFoundry() throws LlmValidationException {
        when(modelFactory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(Duration.class),
                        anyInt(),
                        any(),
                        any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenReturn("Hey there!");

        boolean result = service.validate("foundry", "qwen3-14b", "not-needed");

        assertTrue(result);
        verify(vault, never()).storeSystemKey(anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("validate maps each provider to its expected base URL")
    void validateMapsProviderToBaseUrl() throws LlmValidationException {
        when(modelFactory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(Duration.class),
                        anyInt(),
                        any(),
                        any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenReturn("ok");

        service.validate("groq", "model", "key");

        verify(modelFactory)
                .createModel(
                        eq("groq"),
                        eq("https://api.groq.com/openai/v1"),
                        eq("key"),
                        eq("model"),
                        eq(10),
                        any(Duration.class),
                        eq(1),
                        any(),
                        any());
    }

    @Test
    @DisplayName("validate maps foundry to its expected base URL")
    void validateMapsFoundryToBaseUrl() throws LlmValidationException {
        when(modelFactory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        any(Duration.class),
                        anyInt(),
                        any(),
                        any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenReturn("ok");

        service.validate("foundry", "qwen3-14b", "not-needed");

        verify(modelFactory)
                .createModel(
                        eq("foundry"),
                        eq(ModelDefaults.DEFAULT_FOUNDRY_BASE_URL),
                        eq("not-needed"),
                        eq("qwen3-14b"),
                        eq(10),
                        any(Duration.class),
                        eq(1),
                        any(),
                        any());
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate throws LlmValidationException on chat failure")
    void validateThrowsOnChatFailure() {
        when(modelFactory.createModel(
                        anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenThrow(new RuntimeException("401 Unauthorized"));

        LlmValidationException ex =
                assertThrows(LlmValidationException.class, () -> service.validate("openai", "gpt-4o", "bad-key"));

        assertTrue(ex.getMessage().contains("401 Unauthorized"));
    }

    @Test
    @DisplayName("validate unwraps CompletionException before throwing")
    void validateUnwrapsCompletionException() {
        when(modelFactory.createModel(
                        anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(chatModel);
        when(chatModel.chat("hi")).thenAnswer(_ -> {
            throw new CompletionException(new RuntimeException("rate limited"));
        });

        LlmValidationException ex =
                assertThrows(LlmValidationException.class, () -> service.validate("openai", "gpt-4o", "key"));

        assertTrue(ex.getMessage().contains("rate limited"));
    }
}
