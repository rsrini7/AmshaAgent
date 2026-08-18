package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.core.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Component - ClaudeCodeProviderFactory.
 * Creates a ClaudeCodeResponsesChatModel using credentials from Claude Code CLI or environment.
 */
@ApplicationScoped
public class ClaudeCodeProviderFactory implements RoutingChatModelProvider {

    @Inject
    ClaudeCodeTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.claude-code.base-url", defaultValue = ModelDefaults.DEFAULT_CLAUDE_CODE_BASE_URL)
    String configuredBaseUrl;

    @ConfigProperty(name = "amsha.llm.temperature", defaultValue = "0.1")
    double defaultTemperature;

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_CLAUDE_CODE;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String token = tokenResolver.resolveToken(config.apiKey());

        String modelName = config.modelName();
        if (modelName == null || modelName.isBlank() || "mistral-small".equalsIgnoreCase(modelName)) {
            modelName = ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL;
        }

        String baseUrl =
                (config.baseUrl() != null && !config.baseUrl().isBlank()) ? config.baseUrl() : configuredBaseUrl;

        return new ClaudeCodeResponsesChatModel(
                baseUrl,
                token,
                modelName,
                config.maxTokens().orElse(4096),
                config.timeout().orElse(Duration.ofMinutes(3)),
                defaultTemperature,
                governedTransport);
    }
}
