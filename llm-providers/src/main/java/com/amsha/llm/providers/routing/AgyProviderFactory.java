package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.wrapper.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Component - AgyProviderFactory.
 * Creates an AgyResponsesChatModel using credentials from Antigravity CLI (agy) or environment.
 */
@ApplicationScoped
@Unremovable
public class AgyProviderFactory implements RoutingChatModelProvider {

    @Inject
    AgyTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.agy.base-url", defaultValue = ModelDefaults.DEFAULT_AGY_BASE_URL)
    String configuredBaseUrl;

    @ConfigProperty(name = "amsha.llm.temperature", defaultValue = "0.1")
    double defaultTemperature;

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_AGY;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String token = tokenResolver.resolveToken(config.apiKey());

        String modelName = config.modelName();
        if (modelName == null || modelName.isBlank() || "mistral-small".equalsIgnoreCase(modelName)) {
            modelName = ModelDefaults.DEFAULT_AGY_MODEL;
        }

        String baseUrl =
                (config.baseUrl() != null && !config.baseUrl().isBlank()) ? config.baseUrl() : configuredBaseUrl;

        return new AgyResponsesChatModel(
                baseUrl,
                token,
                modelName,
                config.maxTokens().orElse(8192),
                config.timeout().orElse(Duration.ofMinutes(3)),
                defaultTemperature,
                governedTransport);
    }
}
