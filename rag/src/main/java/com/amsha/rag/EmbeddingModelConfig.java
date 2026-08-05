package com.amsha.rag;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration bundle for creating an EmbeddingModel.
 * <p>
 * Passed to {@link EmbeddingProviderFactory#create(EmbeddingModelConfig)}.
 */
public record EmbeddingModelConfig(
        String providerId,
        String modelName,
        String baseUrl,
        Optional<String> apiKey,
        Duration timeout,
        String spaceId,
        String spaceRevision) {

    public EmbeddingModelConfig(
            String providerId, String modelName, String baseUrl, Optional<String> apiKey, Duration timeout) {
        this(providerId, modelName, baseUrl, apiKey, timeout, "", "");
    }
}
