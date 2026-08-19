package com.amsha.llm.providers.helper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;
import com.amsha.llm.api.oauth.ClaudeCodeOAuthTokenExtractor;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Shared token resolution helper for the Claude Code CLI / Anthropic provider.
 * Extracts and caches credentials across agent reasoning and routing factories.
 */
@ApplicationScoped
@Unremovable
public class ClaudeCodeTokenResolver {

    private static final Logger log = Logger.getLogger(ClaudeCodeTokenResolver.class);

    @Inject
    ClaudeCodeOAuthTokenExtractor tokenExtractor;

    @Inject
    SecretVault secretVault;

    /**
     * Resolves the Claude Code token with no explicit key override.
     */
    public String resolveToken() {
        return resolveToken(Optional.empty());
    }

    /**
     * Resolves the Claude Code token, checking an optional explicit key, then vault,
     * then OAuth extractor, and finally system/environment variables.
     */
    public String resolveToken(Optional<String> explicitApiKey) {
        if (explicitApiKey != null
                && explicitApiKey.isPresent()
                && !explicitApiKey.get().isBlank()) {
            return explicitApiKey.get();
        }

        if (secretVault != null) {
            Optional<VaultKey> claudeKey = secretVault.retrieveSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY);
            if (claudeKey.isPresent()) {
                try (VaultKey keyHandle = claudeKey.get()) {
                    String token = new String(keyHandle.get(), StandardCharsets.UTF_8).trim();
                    if (!token.isBlank()) {
                        return token;
                    }
                }
            }
        }

        if (tokenExtractor != null) {
            Optional<String> extracted = tokenExtractor.extractToken();
            if (extracted.isPresent()) {
                if (secretVault != null) {
                    byte[] tokenBytes = extracted.get().getBytes(StandardCharsets.UTF_8);
                    try {
                        secretVault.storeSystemKey(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY, tokenBytes);
                        log.info("Claude Code provider: token loaded and cached in vault.");
                    } finally {
                        Arrays.fill(tokenBytes, (byte) 0);
                    }
                }
                return extracted.get();
            }
        }

        throw new IllegalStateException(
                "Claude Code provider requires active credentials. "
                        + "Please run 'claude login' in your local terminal first, or set AMSHA_CLAUDE_CODE_API_KEY / CLAUDE_CODE_OAUTH_TOKEN.");
    }
}
