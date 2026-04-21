package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LlmModelFactoryTest {

    @Inject
    LlmModelFactory factory;

    @Test
    void testCreateGroqModel() {
        ChatModel model = factory.createModel(
                "groq", "https://api.groq.com/openai/v1", "test-key",
                "llama3-8b", 100, null, 1, null, null);
        assertNotNull(model);
    }

    @Test
    void testCreateOpenAiModel() {
        ChatModel model = factory.createModel(
                "openai", "https://api.openai.com/v1", "test-key",
                "gpt-4", 200, null, 1, null, null);
        assertNotNull(model);
    }

    @Test
    void testCreateOllamaModel() {
        ChatModel model = factory.createModel(
                "ollama", null, null, null, null, null, 1,
                "http://localhost:11434", "llama3.2");
        assertNotNull(model);
    }

    @Test
    void testCreateCerebrasModel() {
        ChatModel model = factory.createModel(
                "cerebras", "https://api.cerebras.ai/v1", "test-key",
                "llama3.1-8b", 100, null, 1, null, null);
        assertNotNull(model);
    }

    @Test
    void testUnknownProvider() {
        assertThrows(IllegalStateException.class, () -> {
            factory.createModel("unknown", null, null, null, null, null, 1, null, null);
        });
    }
}
