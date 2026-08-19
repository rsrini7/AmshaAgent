package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.helper.AgyTokenResolver;
import com.amsha.llm.providers.wrapper.AgyResponsesChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * {@link AgentChatModelProvider} for Antigravity (agy) / Google Gemini models using agy CLI credentials.
 * Mirrors {@link com.amsha.core.routing.AgyProviderFactory} for the agent reasoning engine.
 */
@ApplicationScoped
@Unremovable
public class AgyProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    AgyTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.agy.model", defaultValue = ModelDefaults.DEFAULT_AGY_MODEL)
    String modelName;

    @ConfigProperty(name = "amsha.llm.agy.base-url", defaultValue = ModelDefaults.DEFAULT_AGY_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.agy.api-key")
    Optional<String> apiKey;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_AGY;
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        return doCreate(null);
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        String token = tokenResolver.resolveToken(apiKey);

        String resolvedModel = overrideModel != null ? overrideModel : modelName;
        if (resolvedModel == null || resolvedModel.isBlank() || "mistral-small".equalsIgnoreCase(resolvedModel)) {
            resolvedModel = ModelDefaults.DEFAULT_AGY_MODEL;
        }

        return new AgyResponsesChatModel(
                baseUrl,
                token,
                resolvedModel,
                agentConfig.maxTokens(),
                agentConfig.timeout(),
                agentConfig.temperature(),
                governedTransport);
    }
}
