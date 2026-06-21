package com.amsha.core.routing;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Component A3 - ClaudeCodeProviderFactory.
 * Creates an Anthropic chat model using credentials from Claude Code CLI.
 */
@ApplicationScoped
public class ClaudeCodeProviderFactory implements RoutingChatModelProvider {

    private static final Logger log = Logger.getLogger(ClaudeCodeProviderFactory.class);

    @Inject
    ClaudeCodeOAuthTokenExtractor tokenExtractor;

    @Inject
    SecretVault secretVault;

    @ConfigProperty(name = "amsha.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @Override
    public String providerId() {
        return "claude-code";
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        // 1. Try to retrieve token from vault using the standard system key (Fix I5)
        Optional<String> token = secretVault
                .retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY)
                .map(VaultKey::get)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));

        if (token.isEmpty()) {
            // 2. Fall back to tokenExtractor (filesystem extraction from ~/.claude/credentials.json)
            token = tokenExtractor.extractToken();

            if (token.isPresent()) {
                // 3. Store in vault for future use to avoid repeated FS access
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

        // 5. Clean up model name assignment (Fix I1)
        String modelName = config.modelName();
        if (modelName == null || modelName.isBlank() || "mistral-small".equalsIgnoreCase(modelName)) {
            modelName = "claude-sonnet-4-5";
        }

        return AnthropicChatModel.builder()
                .apiKey(token.get())
                .modelName(modelName)
                .timeout(config.timeout().orElse(Duration.ofMinutes(3)))
                .logRequests(logHttp)
                .logResponses(logHttp)
                .build();
    }
}
