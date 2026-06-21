package com.arcclaw.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * local Ollama implementation of the EmbeddingProvider SPI.
 */
@ApplicationScoped
public class OllamaEmbeddingProviderFactory implements EmbeddingProviderFactory {

    private static final Logger log = Logger.getLogger(OllamaEmbeddingProviderFactory.class);

    @Override
    public String providerId() {
        return "ollama";
    }

    @Override
    public String defaultBaseUrl() {
        return "http://localhost:11434";
    }

    @Override
    public EmbeddingModel create(EmbeddingModelConfig config) {
        log.infof("Creating local Ollama embedding model: %s url=%s", config.modelName(), config.baseUrl());
        return OllamaEmbeddingModel.builder()
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .timeout(config.timeout())
                .build();
    }
}
