package com.arcclaw.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * Jina AI implementation of the EmbeddingProvider SPI.
 * Uses OpenAiEmbeddingModel under the hood as Jina is OpenAI-compatible.
 */
@ApplicationScoped
public class JinaEmbeddingProviderFactory implements EmbeddingProviderFactory {

    private static final Logger log = Logger.getLogger(JinaEmbeddingProviderFactory.class);

    @Override
    public String providerId() {
        return "jina";
    }

    @Override
    public EmbeddingModel create(EmbeddingModelConfig config) {
        String key = requireApiKey(config);
        // Jina is OpenAI-compatible; default to its own model when the Ollama default
        // leaks through (e.g. via shared config templates).
        String jinaModel = config.modelName().equals("nomic-embed-text:v1.5")
                ? "jina-embeddings-v5-text-nano"
                : config.modelName();
        log.infof("Creating Jina embedding model: %s (via OpenAI adapter) base=%s", jinaModel, config.baseUrl());

        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(key)
                .modelName(jinaModel)
                .timeout(config.timeout())
                .build();
    }
}
