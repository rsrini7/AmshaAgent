package com.arcclaw.core.routing;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * {@link RoutingChatModelProvider} for the Groq inference API.
 * Groq uses the OpenAI API protocol but with its own base URL and key.
 */
@ApplicationScoped
public class GroqLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "groq";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        var builder = OpenAiChatModel.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey()
                .orElseThrow(() -> new IllegalStateException(
                    "Groq provider requires an API key (ARCCLAW_LLM_API_KEY)")))
            .modelName(config.modelName())
            .maxTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
            .maxRetries(config.maxRetries().orElse(3))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false);

        if (config.structuredOutputMode() == StructuredOutputMode.NATIVE && config.toolless()) {
            builder.responseFormat(ResponseFormat.JSON);
        }

        return builder.build();
    }
}
