package com.arcclaw.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * SPI for embedding platform providers.
 * <p>
 * Allows adding new embedding models (Cohere, Mistral, etc.) by implementing this interface
 * as a CDI bean, without modifying {@code EmbeddingModelProducer}.
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
                .orElseThrow(() -> new IllegalStateException("arcclaw.rag.embed-api-key is required for provider '"
                        + providerId() + "'. " + "Set ARCCLAW_EMBED_API_KEY in .env"));
    }
}
