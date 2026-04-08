package com.arcclaw.app.compaction;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.LlmModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionModelProducer {

    private static final Logger log = Logger.getLogger(CompactionModelProducer.class);

    @ConfigProperty(name = "arcclaw.memory.compaction.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "arcclaw.memory.compaction.base-url")
    String compactionBaseUrl;

    @ConfigProperty(name = "arcclaw.memory.compaction.api-key")
    String compactionApiKey;

    @ConfigProperty(name = "arcclaw.memory.compaction.model", defaultValue = "llama-3.1-8b-instant")
    String modelId;

    @ConfigProperty(name = "arcclaw.memory.compaction.max-tokens", defaultValue = "1024")
    int compactionMaxTokens;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.memory.compaction.ollama-model", defaultValue = "llama3.2")
    String ollamaModelId;

    @ConfigProperty(name = "arcclaw.memory.compaction.max-retries", defaultValue = "3")
    int maxRetries;

    @Inject
    LlmModelFactory modelFactory;

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatModel compactionModel() {
        log.infof("CompactionModelProducer provider=%s model=%s", provider, modelId);
        return modelFactory.createModel(
            provider, compactionBaseUrl, compactionApiKey,
            modelId, compactionMaxTokens, Duration.ofSeconds(10),
            maxRetries, ollamaBaseUrl, ollamaModelId);
    }
}