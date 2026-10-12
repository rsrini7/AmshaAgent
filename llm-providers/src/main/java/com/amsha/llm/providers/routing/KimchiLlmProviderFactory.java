package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.helper.KimchiTokenResolver;
import com.amsha.llm.providers.wrapper.KimchiResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for the Kimchi (kimchi.dev) inference API.
 * Connects over OpenAI-compatible protocol with required custom User-Agent header and governed transport.
 */
@ApplicationScoped
@Unremovable
public class KimchiLlmProviderFactory implements RoutingChatModelProvider {

    @Inject
    KimchiTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = LlmConfigKeys.KIMCHI_BASE_URL, defaultValue = ModelDefaults.DEFAULT_KIMCHI_BASE_URL)
    String configuredBaseUrl;

    @ConfigProperty(name = LlmConfigKeys.TEMPERATURE, defaultValue = "0.1")
    double defaultTemperature;

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_KIMCHI;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String key = tokenResolver.resolveToken(config.apiKey());

        String modelName = config.modelName();
        if (modelName == null || modelName.isBlank() || ModelDefaults.PLACEHOLDER_MODEL.equalsIgnoreCase(modelName)) {
            modelName = ModelDefaults.DEFAULT_KIMCHI_MODEL;
        }

        String baseUrl =
                (config.baseUrl() != null && !config.baseUrl().isBlank()) ? config.baseUrl() : configuredBaseUrl;

        return new KimchiResponsesChatModel(
                baseUrl,
                key,
                modelName,
                config.maxTokens().orElse(8192),
                config.timeout().orElse(Duration.ofMinutes(3)),
                defaultTemperature,
                governedTransport);
    }
}
