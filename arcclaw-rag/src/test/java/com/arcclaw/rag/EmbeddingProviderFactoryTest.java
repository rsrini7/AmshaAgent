package com.arcclaw.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class EmbeddingProviderFactoryTest {

    private final EmbeddingModelConfig config = new EmbeddingModelConfig(
        "openai", "text-embedding-3-small", "https://api.openai.com/v1",
        Optional.of("test-key"), Duration.ofSeconds(30)
    );

    @Test
    void testOpenAiProviderId() {
        OpenAiEmbeddingProviderFactory factory = new OpenAiEmbeddingProviderFactory();
        assertEquals("openai", factory.providerId());
    }

    @Test
    void testOpenAiRequiresApiKey() {
        OpenAiEmbeddingProviderFactory factory = new OpenAiEmbeddingProviderFactory();
        EmbeddingModelConfig noKey = new EmbeddingModelConfig(
            "openai", "text-embedding-3-small", "https://api.openai.com/v1",
            Optional.empty(), Duration.ofSeconds(30)
        );
        assertThrows(IllegalStateException.class, () -> factory.create(noKey));
    }

    @Test
    void testOllamaProviderId() {
        OllamaEmbeddingProviderFactory factory = new OllamaEmbeddingProviderFactory();
        assertEquals("ollama", factory.providerId());
    }

    @Test
    void testOllamaCreateDoesNotThrow() {
        OllamaEmbeddingProviderFactory factory = new OllamaEmbeddingProviderFactory();
        EmbeddingModelConfig ollamaConfig = new EmbeddingModelConfig(
            "ollama", "nomic-embed-text:v1.5", "http://localhost:11434",
            Optional.empty(), Duration.ofSeconds(10)
        );
        assertDoesNotThrow(() -> factory.create(ollamaConfig));
    }

    @Test
    void testJinaProviderId() {
        JinaEmbeddingProviderFactory factory = new JinaEmbeddingProviderFactory();
        assertEquals("jina", factory.providerId());
    }

    @Test
    void testJinaRequiresApiKey() {
        JinaEmbeddingProviderFactory factory = new JinaEmbeddingProviderFactory();
        EmbeddingModelConfig noKey = new EmbeddingModelConfig(
            "jina", "jina-embeddings-v5-text-nano", "https://api.jina.ai",
            Optional.empty(), Duration.ofSeconds(30)
        );
        assertThrows(IllegalStateException.class, () -> factory.create(noKey));
    }

    @Test
    @EnabledIf("com.arcclaw.rag.CdiAvailableCondition#isCdiAvailable")
    void testJinaOverridesDefaultModel() {
        JinaEmbeddingProviderFactory factory = new JinaEmbeddingProviderFactory();
        EmbeddingModelConfig defaultModelConfig = new EmbeddingModelConfig(
            "jina", "nomic-embed-text:v1.5", "https://api.jina.ai",
            Optional.of("test-key"), Duration.ofSeconds(30)
        );
        assertDoesNotThrow(() -> factory.create(defaultModelConfig));
    }

    @Test
    void testRequireApiKeyReturnsKey() {
        OpenAiEmbeddingProviderFactory factory = new OpenAiEmbeddingProviderFactory();
        String key = factory.requireApiKey(config);
        assertEquals("test-key", key);
    }

    @Test
    void testRequireApiKeyThrowsOnEmpty() {
        OpenAiEmbeddingProviderFactory factory = new OpenAiEmbeddingProviderFactory();
        EmbeddingModelConfig emptyKey = new EmbeddingModelConfig(
            "openai", "text-embedding-3-small", "https://api.openai.com/v1",
            Optional.of(""), Duration.ofSeconds(30)
        );
        assertThrows(IllegalStateException.class, () -> factory.requireApiKey(emptyKey));
    }

    @Test
    void testRequireApiKeyThrowsOnAbsent() {
        OpenAiEmbeddingProviderFactory factory = new OpenAiEmbeddingProviderFactory();
        EmbeddingModelConfig noKey = new EmbeddingModelConfig(
            "openai", "text-embedding-3-small", "https://api.openai.com/v1",
            Optional.empty(), Duration.ofSeconds(30)
        );
        assertThrows(IllegalStateException.class, () -> factory.requireApiKey(noKey));
    }
}
