package com.arcclaw.app.compaction;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompactionModelProducer {

    private static final Logger log = Logger.getLogger(CompactionModelProducer.class);

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "arcclaw.memory.compaction.base-url")
    String compactionBaseUrl;

    @ConfigProperty(name = "arcclaw.memory.compaction.api-key")
    String compactionApiKey;

    @ConfigProperty(name = "arcclaw.memory.compaction.model",
                    defaultValue = "llama-3.1-8b-instant")
    String modelId;

    @ConfigProperty(name = "arcclaw.memory.compaction.max-tokens",
                    defaultValue = "1024")
    int compactionMaxTokens;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url",
                    defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.memory.compaction.ollama-model",
                    defaultValue = "llama3.2")
    String ollamaModelId;

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatModel compactionModel() {
        log.infof("CompactionModelProducer: provider=%s model=%s", provider, modelId);
        return switch (provider.toLowerCase()) {
            case "groq" -> OpenAiChatModel.builder()
                    .baseUrl(compactionBaseUrl)
                    .apiKey(compactionApiKey)
                    .modelName(modelId)
                    .maxTokens(compactionMaxTokens)
                    .timeout(Duration.ofSeconds(10))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            case "openai" -> OpenAiChatModel.builder()
                    .baseUrl(compactionBaseUrl)
                    .apiKey(compactionApiKey)
                    .modelName(modelId)
                    .maxTokens(compactionMaxTokens)
                    .timeout(Duration.ofSeconds(10))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(ollamaModelId)
                    .build();
            default -> throw new IllegalStateException(
                    "Unknown LLM provider: " + provider +
                    ". Supported: groq | openai | ollama");
        };
    }
}
