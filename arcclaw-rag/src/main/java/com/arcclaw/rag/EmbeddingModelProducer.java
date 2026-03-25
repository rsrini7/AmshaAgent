package com.arcclaw.rag;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer for EmbeddingModel.
 *
 * Controlled by arcclaw.rag.embed-provider in application.yaml / .env:
 *
 *   ollama   → local Ollama (default, free, private)
 *              Requires: embed-base-url, embed-model
 *
 *   openai   → OpenAI text-embedding-* models (paid)
 *              Requires: embed-api-key, embed-model
 *              embed-base-url defaults to OpenAI, override for Azure etc.
 *
 *   jina     → Jina AI (free tier, 1M tokens/month, no credit card)
 *              Get key: https://jina.ai
 *              Requires: embed-api-key
 *              embed-model defaults to jina-embeddings-v5-text-nano
 *              NOTE: set embed-dimensions=1024 for Jina
 */
@ApplicationScoped
public class EmbeddingModelProducer {

    // private static final Logger log = Logger.getLogger(EmbeddingModelProducer.class);

    @Inject
    @Any
    Instance<EmbeddingProviderFactory> factories;

    @ConfigProperty(name = "arcclaw.rag.embed-provider", defaultValue = "ollama")
    String provider;

    @ConfigProperty(name = "arcclaw.rag.embed-model", defaultValue = "nomic-embed-text:v1.5")
    String modelName;

    @ConfigProperty(name = "arcclaw.rag.embed-base-url", defaultValue = "http://localhost:11434")
    String baseUrl;

    @ConfigProperty(name = "arcclaw.rag.embed-api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "arcclaw.rag.embed-timeout-seconds", defaultValue = "10")
    int timeoutSeconds;

    @Produces
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        String targetProvider = provider.toLowerCase().trim();
        
        return factories.stream()
            .filter(f -> f.providerId().equalsIgnoreCase(targetProvider))
            .findFirst()
            .map(factory -> {
                EmbeddingModelConfig config = new EmbeddingModelConfig(
                    targetProvider, 
                    modelName, 
                    baseUrl, 
                    apiKey, 
                    Duration.ofSeconds(timeoutSeconds)
                );
                return factory.create(config);
            })
            .orElseThrow(() -> new IllegalStateException(
                "No EmbeddingProviderFactory found for provider: '" + targetProvider + "'. " +
                "Available: " + factories.stream()
                    .map(EmbeddingProviderFactory::providerId)
                    .collect(Collectors.joining(", "))));
    }
}
