package com.arcclaw.core.routing;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;

/**
 * {@link RoutingChatModelProvider} for the Cerebras AI inference API.
 * Cerebras uses the OpenAI API protocol.
 */
@ApplicationScoped
public class CerebrasLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "cerebras";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                config.apiKey().orElseThrow(() -> new IllegalStateException(
                        "Cerebras provider requires an API key (ARCCLAW_LLM_API_KEY)")),
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofSeconds(30)),
                config.maxRetries().orElse(3),
                0.0, false, false);

        ChatModelFactory.applyJsonResponseFormat(builder, config.structuredOutputMode(), config.toolless());

        return builder.build();
    }
}
