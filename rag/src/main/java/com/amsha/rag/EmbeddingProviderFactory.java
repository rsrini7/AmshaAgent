package com.amsha.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.Locale;

/**
 * SPI for embedding platform providers.
 * <p>
 * Allows adding new embedding models (Cohere, Mistral, etc.) by implementing this interface
 * as a CDI bean, without modifying {@link InfiniteLoopEmbeddingModelProvider}.
 */
public interface EmbeddingProviderFactory {

    /** Canonical lowercase provider ID, e.g. "openai", "ollama", "jina". */
    String providerId();

    /** Default base URL used if none is configured. */
    default String defaultBaseUrl() {
        return "http://localhost:11434";
    }

    /** Builds the model for this provider using the validated config. */
    EmbeddingModel create(EmbeddingModelConfig config);

    /**
     * Describes the vector space emitted by a model created from {@code config}.
     * Defaults intentionally bind a mutable/unknown provider to its own space so it
     * cannot silently participate in cross-provider failover.
     */
    default EmbeddingSpaceDescriptor embeddingSpace(EmbeddingModelConfig config) {
        if (!config.spaceId().isBlank() && !config.spaceRevision().isBlank()) {
            return EmbeddingSpaceDescriptor.configured(providerId(), config.spaceId(), config.spaceRevision());
        }
        return EmbeddingSpaceDescriptor.providerBound(providerId(), config.modelName());
    }

    /** Helper for factories to enforce API key presence. */
    default String requireApiKey(EmbeddingModelConfig config) {
        return config.apiKey()
                .filter(k -> !k.isBlank())
                .orElseThrow(() -> new IllegalStateException("An API key is required for embedding provider '"
                        + providerId() + "'. "
                        + "Set AMSHA_EMBEDDING_" + providerId().toUpperCase(Locale.ROOT)
                        + "_API_KEY in .env (or switch the provider in amsha.infinite-loop.embedding.providers)."));
    }
}
