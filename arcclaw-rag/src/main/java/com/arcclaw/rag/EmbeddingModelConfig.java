package com.arcclaw.rag;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration bundle for creating an EmbeddingModel.
 * <p>
 * Passed to {@link EmbeddingProviderFactory#create(EmbeddingModelConfig)}.
 */
public record EmbeddingModelConfig(
        String providerId, String modelName, String baseUrl, Optional<String> apiKey, Duration timeout) {}
