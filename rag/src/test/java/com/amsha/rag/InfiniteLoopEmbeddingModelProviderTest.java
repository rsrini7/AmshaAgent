package com.amsha.rag;

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
        when(mockOllamaFactory.embeddingSpace(any(EmbeddingModelConfig.class)))
                .thenReturn(EmbeddingSpaceDescriptor.providerBound("ollama", "nomic-embed-text:v1.5"));
        when(mockFactories.stream()).thenAnswer(i -> Stream.of(mockOllamaFactory));
        provider.factories = mockFactories;

        // Defaults
        provider.loopProviders = "ollama";
        provider.cooldownBaseSeconds = 30;
        provider.cooldownMaxSeconds = 300;
        provider.cooldownTransientSeconds = 5;
        provider.maxLatencyMs = 60000;
        provider.configuredVectorDimension = 768;

        // Set per-provider config via system properties
        System.setProperty("amsha.embedding.ollama.model", "nomic-embed-text:v1.5");
        System.setProperty("amsha.embedding.ollama.base-url", "http://localhost:11434");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("amsha.embedding.ollama.model");
        System.clearProperty("amsha.embedding.ollama.base-url");
    }

    @Test
    void testSingleProviderCreatesEmbeddingModel() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockOllamaFactory.create(any(EmbeddingModelConfig.class))).thenReturn(mockModel);

        EmbeddingModel result = provider.embeddingModel();

        assertNotNull(result);
        assertEquals(768, provider.activeDescriptor().orElseThrow().vectorDimension());
        verify(mockOllamaFactory).create(any(EmbeddingModelConfig.class));
    }

    @Test
    void propagatesProviderChunkingContractToIndexDescriptor() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockOllamaFactory.create(any(EmbeddingModelConfig.class))).thenReturn(mockModel);
        when(mockOllamaFactory.embeddingSpace(any(EmbeddingModelConfig.class)))
                .thenReturn(EmbeddingSpaceDescriptor.verified(
                        "ollama",
                        "verified-space",
                        "model-v1",
                        "tokenizer-v1",
                        "preprocessing-v1",
                        "MASKED_MEAN",
                        true,
                        768,
                        "FLOAT32",
                        512,
                        "MEAN_OVERLAPPING_WINDOWS",
                        "v2",
                        384,
                        32,
                        "WEIGHTED_MEAN"));

        provider.embeddingModel();

        var descriptor = provider.activeDescriptor().orElseThrow();
        assertEquals("MEAN_OVERLAPPING_WINDOWS", descriptor.chunkingStrategy());
        assertEquals("v2", descriptor.chunkingRevision());
        assertEquals(384, descriptor.maxWindowTokens());
        assertEquals(32, descriptor.windowOverlapTokens());
        assertEquals("WEIGHTED_MEAN", descriptor.multiWindowAggregation());
    }

    @Test
    void testThrowsWhenNoValidSlots() {
        provider.loopProviders = "unsupported";
        System.setProperty("amsha.test.force-error", "true");
        try {
            assertThrows(IllegalStateException.class, () -> provider.embeddingModel());
        } finally {
            System.clearProperty("amsha.test.force-error");
        }
    }
}
