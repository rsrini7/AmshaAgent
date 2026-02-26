package com.arcclaw.app.compaction;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class CompactionModelProducer {
    @ConfigProperty(name = "quarkus.langchain4j.groq.base-url", defaultValue = "https://api.groq.com/openai/v1/")
    String groqBaseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.groq.api-key", defaultValue = "mock-key-must-be-overridden")
    String apiKey;

    @ConfigProperty(name = "arcclaw.memory.compaction.model", defaultValue = "llama-3.1-8b-instant")
    String modelId;

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatLanguageModel compactionModel() {
        return OpenAiChatModel.builder()
            .baseUrl(groqBaseUrl)
            .apiKey(apiKey)
            .modelName(modelId)
            .maxTokens(256)
            .timeout(Duration.ofSeconds(10))
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
