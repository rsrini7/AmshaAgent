package com.arcclaw.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class EmbeddingModelProducer {

    @ConfigProperty(name = "arcclaw.rag.embed-model",
                    defaultValue = "nomic-embed-text:1.5")
    String modelName;

    @ConfigProperty(name = "arcclaw.rag.embed-base-url",
                    defaultValue = "http://localhost:11434")
    String baseUrl;

    @Produces
    @ApplicationScoped
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .timeout(Duration.ofSeconds(3))
            .build();
    }
}
