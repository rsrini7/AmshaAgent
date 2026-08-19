package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.wrapper.*;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Sarvam's v1 OpenAI-compatible chat endpoint.
 *
 * <p>The v1 contract accepts standard bearer authentication. This adapter reuses the
 * shared OpenAI transport and supports native JSON response formatting for toolless slots.
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
        var builder = ChatModelFactory.openAiBuilder(
                config.baseUrl(),
                config.apiKey()
                        .orElseThrow(() -> new IllegalStateException(
                                "Sarvam provider requires an API key (AMSHA_SARVAM_API_KEY)")),
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
