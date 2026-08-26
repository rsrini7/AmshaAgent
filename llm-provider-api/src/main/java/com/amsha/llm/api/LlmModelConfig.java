package com.amsha.llm.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration bundle passed to {@link RoutingChatModelProvider#create}.
 * Mirrors {@code EmbeddingModelConfig} from amsha-rag.
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
        boolean toolless,
        boolean strictJsonSchemaAdvertised) {
    public LlmModelConfig {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(structuredOutputMode, "structuredOutputMode");
    }

    /**
     * Backward-compatible constructor: strict-schema advertisement defaults to
     * {@code false} (fail-closed).
     */
    public LlmModelConfig(
            String provider,
            String modelName,
            String baseUrl,
            Optional<String> apiKey,
            Optional<Integer> maxTokens,
            Optional<Duration> timeout,
            Optional<Integer> maxRetries,
            StructuredOutputMode structuredOutputMode,
            boolean toolless) {
        this(
                provider,
                modelName,
                baseUrl,
                apiKey,
                maxTokens,
                timeout,
                maxRetries,
                structuredOutputMode,
                toolless,
                false);
    }
}
