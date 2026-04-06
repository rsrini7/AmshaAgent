package com.arcclaw.core.routing;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration bundle passed to {@link LlmProviderFactory#create}.
 * Mirrors {@code EmbeddingModelConfig} from arcclaw-rag.
 */
public record LlmModelConfig(
        String provider,
        String modelName,
        String baseUrl,
        Optional<String> apiKey,
        Optional<Integer> maxTokens,
        Optional<Duration> timeout
) {
    public LlmModelConfig {
        Objects.requireNonNull(provider,   "provider");
        Objects.requireNonNull(modelName,  "modelName");
        Objects.requireNonNull(baseUrl,    "baseUrl");
    }
}