package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.routing.AgyResponsesChatModel;
import com.amsha.core.routing.AgyTokenResolver;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for Antigravity (agy) / Google Gemini models using agy CLI credentials.
 * Mirrors {@link com.amsha.core.routing.AgyProviderFactory} for the agent reasoning engine.
 */
@ApplicationScoped
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
