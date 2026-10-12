package com.amsha.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.llm.api.ModelDefaults;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * OpenAI implementation of the EmbeddingProvider SPI.
 */
@ApplicationScoped
@Unremovable
public class OpenAiEmbeddingProviderFactory implements EmbeddingProviderFactory {

    private static final Logger log = Logger.getLogger(OpenAiEmbeddingProviderFactory.class);

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OPENAI;
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.openai.com/v1";
    }

    @Override
    public EmbeddingModel create(EmbeddingModelConfig config) {
        String key = requireApiKey(config);
        log.infof("Creating OpenAI embedding model: %s", config.modelName());
        return OpenAiEmbeddingModel.builder()
                .apiKey(key)
                .modelName(config.modelName())
                .timeout(config.timeout())
                .build();
    }
}
