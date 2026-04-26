package com.arcclaw.core.routing;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;

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
        Optional<Duration> timeout,
        Optional<Integer> maxRetries,
        StructuredOutputMode structuredOutputMode,
        boolean toolless
) {
    public LlmModelConfig {
        Objects.requireNonNull(provider,   "provider");
        Objects.requireNonNull(modelName,  "modelName");
        Objects.requireNonNull(baseUrl,    "baseUrl");
        Objects.requireNonNull(structuredOutputMode, "structuredOutputMode");
    }
}
