package com.arcclaw.rag;

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

    /** Builds the model for this provider using the validated config. */
    EmbeddingModel create(EmbeddingModelConfig config);

    /** Helper for factories to enforce API key presence. */
    default String requireApiKey(EmbeddingModelConfig config) {
        return config.apiKey()
                .filter(k -> !k.isBlank())
                .orElseThrow(() -> new IllegalStateException("An API key is required for embedding provider '"
                        + providerId() + "'. "
                        + "Set ARCCLAW_EMBEDDING_" + providerId().toUpperCase(Locale.ROOT)
                        + "_API_KEY in .env (or switch the provider in arcclaw.infinite-loop.embedding.providers)."));
    }
}
