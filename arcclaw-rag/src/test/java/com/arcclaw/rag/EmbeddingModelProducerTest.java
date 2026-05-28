package com.arcclaw.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.enterprise.inject.Instance;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbeddingModelProducerTest {

    private EmbeddingModelProducer producer;
    private Instance<EmbeddingProviderFactory> mockFactories;
    private EmbeddingProviderFactory mockOpenAiFactory;
    private EmbeddingProviderFactory mockOllamaFactory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        producer = new EmbeddingModelProducer();
        mockFactories = mock(Instance.class);
        mockOpenAiFactory = mock(EmbeddingProviderFactory.class);
        mockOllamaFactory = mock(EmbeddingProviderFactory.class);

        when(mockOpenAiFactory.providerId()).thenReturn("openai");
        when(mockOllamaFactory.providerId()).thenReturn("ollama");

        when(mockFactories.stream()).thenAnswer(i -> Stream.of(mockOpenAiFactory, mockOllamaFactory));
        producer.factories = mockFactories;
    }

    @Test
    void testDiscoversAndCallsCorrectFactory() {
        producer.provider = "openai";
        producer.modelName = "text-embedding-3-small";
        producer.apiKey = Optional.of("test-key");
        producer.timeoutSeconds = 30;

        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        when(mockOpenAiFactory.create(any(EmbeddingModelConfig.class))).thenReturn(mockModel);

        EmbeddingModel result = producer.embeddingModel();

        assertSame(mockModel, result);
        verify(mockOpenAiFactory)
                .create(argThat(cfg -> cfg.providerId().equals("openai")
                        && cfg.modelName().equals("text-embedding-3-small")
                        && cfg.apiKey().get().equals("test-key")));
        verifyNoInteractions(mockOllamaFactory);
    }

    @Test
    void testThrowsWhenProviderNotFound() {
        producer.provider = "unsupported";

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> producer.embeddingModel());
        assertTrue(ex.getMessage().contains("No EmbeddingProviderFactory found for provider: 'unsupported'"));
        assertTrue(ex.getMessage().contains("openai, ollama"));
    }
}
