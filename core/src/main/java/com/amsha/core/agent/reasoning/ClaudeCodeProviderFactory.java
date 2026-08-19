package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.routing.ClaudeCodeResponsesChatModel;
import com.amsha.core.routing.ClaudeCodeTokenResolver;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for Anthropic Claude models using Claude Code CLI credentials.
 * Mirrors {@link com.amsha.core.routing.ClaudeCodeProviderFactory} for the agent reasoning engine.
 */
@ApplicationScoped
public class ClaudeCodeProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    ClaudeCodeTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.claude-code.model", defaultValue = ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL)
    String modelName;

    @ConfigProperty(name = "amsha.llm.claude-code.base-url", defaultValue = ModelDefaults.DEFAULT_CLAUDE_CODE_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.claude-code.api-key")
    Optional<String> apiKey;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_CLAUDE_CODE;
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
            resolvedModel = ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL;
        }

        return new ClaudeCodeResponsesChatModel(
                baseUrl,
                token,
                resolvedModel,
                agentConfig.maxTokens(),
                agentConfig.timeout(),
                agentConfig.temperature(),
                governedTransport);
    }
}
