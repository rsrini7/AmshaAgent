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
    void testUnknownProvider() {
        assertThrows(IllegalStateException.class, () -> {
            factory.createModel("unknown", null, null, null, null, null, 1, null, null);
        });
    }
}
