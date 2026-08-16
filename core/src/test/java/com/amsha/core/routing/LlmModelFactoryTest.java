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
    void testUnknownProvider() {
        assertThrows(IllegalStateException.class, () -> {
            factory.createModel("unknown", null, null, null, null, null, 1, null, null);
        });
    }
}
