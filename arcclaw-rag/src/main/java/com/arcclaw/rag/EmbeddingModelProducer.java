package com.arcclaw.rag;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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

    private static final Logger log = Logger.getLogger(EmbeddingModelProducer.class);

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
        return switch (provider.toLowerCase().trim()) {

            case "openai" -> {
                String key = requireApiKey("openai");
                log.infof("EmbeddingModelProducer: provider=openai model=%s", modelName);
                yield OpenAiEmbeddingModel.builder()
                    .apiKey(key)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            }

            case "jina" -> {
                String key = requireApiKey("jina");
                String jinaModel = modelName.equals("nomic-embed-text:v1.5")
                    ? "jina-embeddings-v5-text-nano"   // override Ollama default with Jina default
                    : modelName;
                log.infof("EmbeddingModelProducer: provider=jina model=%s url=%s",
                    jinaModel, baseUrl);
                yield OpenAiEmbeddingModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(key)
                    .modelName(jinaModel)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            }

            case "ollama" -> {
                log.infof("EmbeddingModelProducer: provider=ollama model=%s url=%s",
                    modelName, baseUrl);
                yield OllamaEmbeddingModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            }

            default -> throw new IllegalArgumentException(
                "Unknown arcclaw.rag.embed-provider: '" + provider + "'. " +
                "Supported values: ollama, openai, jina");
        };
    }

    private String requireApiKey(String providerName) {
        return apiKey.filter(k -> !k.isBlank())
            .orElseThrow(() -> new IllegalStateException(
                "arcclaw.rag.embed-api-key is required for provider '" + providerName + "'. " +
                "Set ARCCLAW_EMBED_API_KEY in .env"));
    }
}
