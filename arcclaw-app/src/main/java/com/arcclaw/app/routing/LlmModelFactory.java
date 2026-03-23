package com.arcclaw.app.routing;

import java.time.Duration;
import jakarta.enterprise.context.ApplicationScoped;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * Common factory for creating LangChain4j ChatModels based on provider configuration.
 * Consolidates the switch logic previously duplicated in ModelProducer and CompactionModelProducer.
 */
@ApplicationScoped
public class LlmModelFactory {

    public ChatModel createModel(String provider, String baseUrl, String apiKey,
                                String modelName, Integer maxTokens, Duration timeout,
                                String ollamaBaseUrl, String ollamaModelId) {
        return switch (provider.toLowerCase()) {
            case "groq", "openai" -> OpenAiChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .temperature(0.0)
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
