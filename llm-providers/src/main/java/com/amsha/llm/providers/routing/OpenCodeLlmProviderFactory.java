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
 * {@link RoutingChatModelProvider} for OpenCode's OpenAI-compatible chat endpoint (Zen / Go).
 *
 * <p>The OpenCode contract accepts standard bearer authentication. This adapter reuses the
 * shared OpenAI transport and supports native JSON response formatting for toolless slots.
 */
@ApplicationScoped
@Unremovable
public class OpenCodeLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OPENCODE;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String cleanKey = config.apiKey()
                .orElseThrow(() ->
                        new IllegalStateException("OpenCode provider requires an API key (AMSHA_OPENCODE_API_KEY)"))
                .replaceAll("[\\p{Cntrl}\\s\"']", "")
                .trim();
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
        builder.customHeaders(Map.of("User-Agent", "AmshaAgent/1.0"));

        ChatModelFactory.applyJsonResponseFormat(
                builder, config.structuredOutputMode(), config.toolless(), config.baseUrl());

        return builder.build();
    }
}
