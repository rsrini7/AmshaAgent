package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.wrapper.NvidiaMessageSanitizer;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class NvidiaLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_NVIDIA;
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
