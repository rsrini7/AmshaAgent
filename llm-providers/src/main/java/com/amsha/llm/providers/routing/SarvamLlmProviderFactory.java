package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Sarvam's v1 OpenAI-compatible chat endpoint.
 *
 * <p>Sarvam's production gateway requires the {@code api-subscription-key} header. This adapter reuses the
 * shared OpenAI transport, supplies the required subscription key header, and supports native JSON response formatting.
 */
@ApplicationScoped
@Unremovable
public class SarvamLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_SARVAM;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String key = config.apiKey()
                .orElseThrow(
                        () -> new IllegalStateException("Sarvam provider requires an API key (AMSHA_SARVAM_API_KEY)"));
        String cleanKey = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();

        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                cleanKey,
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofSeconds(30)),
                config.maxRetries().orElse(3),
                0.0,
                false,
                false);
        builder.customHeaders(Map.of("api-subscription-key", cleanKey));

        ChatModelFactory.applyJsonResponseFormat(
                builder, config.structuredOutputMode(), config.toolless(), config.baseUrl());

        return builder.build();
    }
}
