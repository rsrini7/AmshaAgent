package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.arcclaw.core.routing.ClaudeCodeOAuthTokenExtractor;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultConstants;
import com.arcclaw.core.security.VaultKey;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link AgentChatModelProvider} for Anthropic Claude models using Claude Code CLI credentials.
 * Mirrors {@link com.arcclaw.core.routing.ClaudeCodeProviderFactory} for the agent reasoning engine.
 */
@ApplicationScoped
public class ClaudeCodeProviderFactory implements AgentChatModelProvider {

    private static final Logger log = Logger.getLogger(ClaudeCodeProviderFactory.class);

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    ClaudeCodeOAuthTokenExtractor tokenExtractor;

    @Inject
    SecretVault secretVault;

    @ConfigProperty(name = "arcclaw.llm.model-name", defaultValue = "claude-sonnet-4-5")
    String modelName;

    @Override
    public String providerName() {
        return "claude-code";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        Optional<String> token = secretVault
                .retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY)
                .map(VaultKey::get)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));

        if (token.isEmpty()) {
            token = tokenExtractor.extractToken();
            if (token.isPresent()) {
                secretVault.storeSystemKey(
                        VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY, token.get().getBytes(StandardCharsets.UTF_8));
                log.info("Claude Code provider: token loaded from file and stored in vault.");
            }
        } else {
            log.info("Claude Code provider: token loaded from vault.");
        }

        if (token.isEmpty()) {
            throw new IllegalStateException("Claude Code provider requires an OAuth token. "
                    + "Please run `claude login` in your local terminal first.");
        }

        String resolvedModel = modelName;
        if (resolvedModel == null || resolvedModel.isBlank() || "mistral-small".equalsIgnoreCase(resolvedModel)) {
            resolvedModel = "claude-sonnet-4-5";
        }

        return AnthropicChatModel.builder()
                .apiKey(token.get())
                .modelName(resolvedModel)
                .timeout(agentConfig.timeout())
                .logRequests(agentConfig.logHttp())
                .logResponses(agentConfig.logHttp())
                .build();
    }
}
