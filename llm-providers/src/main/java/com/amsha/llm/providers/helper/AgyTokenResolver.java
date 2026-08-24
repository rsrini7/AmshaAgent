package com.amsha.llm.providers.helper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import com.amsha.llm.api.oauth.AgyOAuthTokenExtractor;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Shared token resolution helper for the Antigravity (agy) / Gemini CLI provider.
 * Extracts and caches credentials across agent reasoning and routing factories.
 */
@ApplicationScoped
@Unremovable
public class AgyTokenResolver {

    private static final Logger log = Logger.getLogger(AgyTokenResolver.class);

    @Inject
    AgyOAuthTokenExtractor tokenExtractor;

    @Inject
    SecretVault secretVault;

    /**
     * Resolves the Antigravity token with no explicit key override.
     */
    public String resolveToken() {
        return resolveToken(Optional.empty());
    }

    /**
     * Resolves the Antigravity token, checking an optional explicit key, then vault,
     * then OAuth extractor, and finally system/environment variables.
     */
    public String resolveToken(Optional<String> explicitApiKey) {
        if (explicitApiKey != null
                && explicitApiKey.isPresent()
                && !explicitApiKey.get().isBlank()) {
            return explicitApiKey.get();
        }

        if (AgyCliExecutor.isAvailable()) {
            return "agy-cli-session";
        }

        if (secretVault != null) {
            Optional<VaultKey> agyKey = secretVault.retrieveSystemKey(VaultConstants.AGY_OAUTH_TOKEN_KEY);
            if (agyKey.isPresent()) {
                try (VaultKey keyHandle = agyKey.get()) {
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
                        secretVault.storeSystemKey(VaultConstants.AGY_OAUTH_TOKEN_KEY, tokenBytes);
                        log.info("Antigravity (agy) provider: token loaded and cached in vault.");
                    } finally {
                        Arrays.fill(tokenBytes, (byte) 0);
                    }
                }
                return extracted.get();
            }
        }

        throw new IllegalStateException(
                "Antigravity provider requires active credentials. "
                        + "Please run 'agy' in your local terminal to authenticate, or set AMSHA_AGY_API_KEY / GEMINI_API_KEY / GOOGLE_API_KEY.");
    }
}
