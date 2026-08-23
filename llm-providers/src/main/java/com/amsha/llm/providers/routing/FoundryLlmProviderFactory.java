package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.wrapper.FoundryResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Microsoft Foundry Local (daemon / CLI).
 * Connects over OpenAI-compatible local protocol with fallback to Foundry CLI execution.
 */
@ApplicationScoped
@Unremovable
public class FoundryLlmProviderFactory implements RoutingChatModelProvider {

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.foundry.base-url", defaultValue = ModelDefaults.DEFAULT_FOUNDRY_BASE_URL)
    String configuredBaseUrl;

    @ConfigProperty(name = "amsha.llm.foundry.model", defaultValue = ModelDefaults.DEFAULT_FOUNDRY_MODEL)
    String defaultModel;

    @ConfigProperty(name = "amsha.llm.temperature", defaultValue = "0.1")
    double defaultTemperature;

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_FOUNDRY;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String modelName = config.modelName();
        if (modelName == null || modelName.isBlank() || "mistral-small".equalsIgnoreCase(modelName)) {
            modelName = defaultModel;
        }

        String baseUrl =
                (config.baseUrl() != null && !config.baseUrl().isBlank()) ? config.baseUrl() : configuredBaseUrl;

        return new FoundryResponsesChatModel(
                baseUrl,
                modelName,
                config.maxTokens().orElse(8192),
                config.timeout().orElse(Duration.ofMinutes(3)),
                defaultTemperature,
                governedTransport);
    }
}
