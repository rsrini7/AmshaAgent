package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Optional;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Cohere (OpenAI Compatibility API).
 *
 * <p>Cohere accepts standard OpenAI chat completion wire format with bearer authentication.
 */
@ApplicationScoped
@Unremovable
public class CohereLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_COHERE;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String apiKey = config.apiKey()
                .filter(k -> !k.isBlank())
                .or(() -> ConfigProvider.getConfig()
                        .getOptionalValue("amsha.llm.cohere.api-key", String.class)
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty("AMSHA_COHERE_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty("amsha.llm.cohere.api-key"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_COHERE_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .orElseThrow(
                        () -> new IllegalStateException("Cohere provider requires an API key (AMSHA_COHERE_API_KEY)"));

        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                apiKey,
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofSeconds(30)),
                config.maxRetries().orElse(3),
                0.0,
                false,
                false);

        ChatModelFactory.applyJsonResponseFormat(
                builder, config.structuredOutputMode(), config.toolless(), config.baseUrl());

        return builder.build();
    }
}
