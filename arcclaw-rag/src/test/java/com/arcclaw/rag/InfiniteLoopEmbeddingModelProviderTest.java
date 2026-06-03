package com.arcclaw.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.inject.Instance;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InfiniteLoopEmbeddingModelProviderTest {

    private InfiniteLoopEmbeddingModelProvider provider;
    private Instance<EmbeddingProviderFactory> mockFactories;
    private EmbeddingProviderFactory mockOllamaFactory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        provider = new InfiniteLoopEmbeddingModelProvider();
        mockFactories = mock(Instance.class);
        mockOllamaFactory = mock(EmbeddingProviderFactory.class);

        when(mockOllamaFactory.providerId()).thenReturn("ollama");
        when(mockFactories.stream()).thenAnswer(i -> Stream.of(mockOllamaFactory));
        provider.factories = mockFactories;

        // Defaults
        provider.loopProviders = "ollama";
        provider.cooldownBaseSeconds = 30;
        provider.cooldownMaxSeconds = 300;
        provider.cooldownTransientSeconds = 5;
        provider.maxLatencyMs = 60000;

        // Set per-provider config via system properties
        System.setProperty("arcclaw.embedding.ollama.model", "nomic-embed-text:v1.5");
        System.setProperty("arcclaw.embedding.ollama.base-url", "http://localhost:11434");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("arcclaw.embedding.ollama.model");
        System.clearProperty("arcclaw.embedding.ollama.base-url");
    }

    @Test
    void testSingleProviderCreatesEmbeddingModel() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockOllamaFactory.create(any(EmbeddingModelConfig.class))).thenReturn(mockModel);

        EmbeddingModel result = provider.embeddingModel();

        assertNotNull(result);
        verify(mockOllamaFactory).create(any(EmbeddingModelConfig.class));
    }

    @Test
    void testThrowsWhenNoValidSlots() {
        provider.loopProviders = "unsupported";
        System.setProperty("arcclaw.test.force-error", "true");
        try {
            assertThrows(IllegalStateException.class, () -> provider.embeddingModel());
        } finally {
            System.clearProperty("arcclaw.test.force-error");
        }
    }
}
