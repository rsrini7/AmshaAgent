package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

@ApplicationScoped
public class NvidiaLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return "nvidia";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        ChatModel base = ChatModelFactory.openAiBuilder(
                        config.baseUrl(),
                        config.apiKey().orElseThrow(() -> new IllegalStateException("NVIDIA requires apiKey")),
                        config.modelName(),
                        config.maxTokens().orElse(null),
                        config.timeout().orElse(Duration.ofSeconds(30)),
                        config.maxRetries().orElse(3),
                        0.0,
                        false,
                        false)
                .build();

        return new NvidiaMessageSanitizer(base);
    }
}
