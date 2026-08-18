package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class LlmModelFactoryTest {

    @Inject
    LlmModelFactory factory;

    @Test
    void testInferProviderFromUrl() {
        assertEquals("groq", LlmModelFactory.inferProviderFromUrl("https://api.groq.com/openai/v1"));
        assertEquals("openai", LlmModelFactory.inferProviderFromUrl("https://api.openai.com/v1"));
        assertEquals("nvidia", LlmModelFactory.inferProviderFromUrl("https://integrate.api.nvidia.com/v1"));
        assertEquals("cerebras", LlmModelFactory.inferProviderFromUrl("https://api.cerebras.ai/v1"));
        assertEquals("sarvam", LlmModelFactory.inferProviderFromUrl("https://api.sarvam.ai/v1"));
        assertEquals("opencode", LlmModelFactory.inferProviderFromUrl("https://opencode.ai/zen/v1"));
        assertEquals("opencode", LlmModelFactory.inferProviderFromUrl("https://opencode.ai/zen/go/v1"));
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
    }

    @Test
    void testCreateGroqModel() {
        ChatModel model = factory.createModel(
                "groq", "https://api.groq.com/openai/v1", "test-key", "llama3-8b", 100, null, 1, null, null);
        assertNotNull(model);
    }

    @Test
    void testCreateOpenAiModel() {
        ChatModel model = factory.createModel(
                "openai", "https://api.openai.com/v1", "test-key", "gpt-4", 200, null, 1, null, null);
        assertNotNull(model);
    }

    @Test
    void testCreateOllamaModel() {
        ChatModel model = factory.createModel(
                "ollama", null, null, null, null, null, 1, "http://localhost:11434", "mistral-small:latest");
        assertNotNull(model);
    }

    @Test
    void testCreateCerebrasModel() {
        ChatModel model = factory.createModel(
                "cerebras",
                "https://api.cerebras.ai/v1",
                "test-key",
                "qwen-3-235b-a22b-instruct-2507",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateSarvamModel() {
        ChatModel model = factory.createModel(
                "sarvam", "https://api.sarvam.ai/v1", "test-key", "sarvam-105b", 100, null, 1, null, null);
        assertNotNull(model);
    }

    @Test
    void testCreateOpenCodeModel() {
        ChatModel model = factory.createModel(
                "opencode",
                "https://opencode.ai/zen/v1",
                "test-key",
                "deepseek-v4-flash-free",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateHuggingFaceModel() {
        ChatModel model = factory.createModel(
                "huggingface",
                "https://router.huggingface.co/v1",
                "hf_test_key",
                "meta-llama/Llama-3.3-70B-Instruct",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateMistralModel() {
        ChatModel model = factory.createModel(
                "mistral",
                "https://api.mistral.ai/v1",
                "test-mistral-key",
                "codestral-latest",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateCloudflareModel() {
        ChatModel model = factory.createModel(
                "cloudflare",
                "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1",
                "test-cf-key",
                "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateCohereModel() {
        ChatModel model = factory.createModel(
                "cohere",
                "https://api.cohere.ai/compatibility/v1",
                "test-cohere-key",
                "command-r-plus-08-2024",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateCodexModel() {
        ChatModel model = factory.createModel(
                "codex",
                "http://127.0.0.1:8787/v1",
                "test-codex-oauth-token",
                "gpt-5.6-luna",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateCodexModel_directOpenAiBaseUrl() {
        ChatModel model = factory.createModel(
                "codex",
                "https://api.openai.com/v1",
                "test-codex-oauth-token",
                "gpt-5.6-luna",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
    }

    @Test
    void testCreateCodexModel_directChatGptResponsesEndpoint() {
        ChatModel model = factory.createModel(
                "codex",
                "https://chatgpt.com/backend-api/codex",
                "test-codex-oauth-token",
                "gpt-5.6-luna",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
        assertTrue(model instanceof CodexResponsesChatModel);
    }

    @Test
    void testCreateClaudeCodeModel() {
        ChatModel model = factory.createModel(
                "claude-code",
                "https://api.anthropic.com",
                "test-claude-token",
                "claude-sonnet-4-5",
                100,
                null,
                1,
                null,
                null);
        assertNotNull(model);
        assertTrue(model instanceof ClaudeCodeResponsesChatModel);
    }

    @Test
    void testCreateProvider_missingApiKey_throwsException() {
        assertThrows(IllegalStateException.class, () -> {
            factory.createModel("groq", "https://api.groq.com/openai/v1", "", "llama3", 100, null, 1, null, null);
        });
    }

    @Test
    void testUnknownProvider() {
        assertThrows(IllegalStateException.class, () -> {
            factory.createModel("unknown", null, null, null, null, null, 1, null, null);
        });
    }
}
