package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;

import org.jboss.logging.Logger;

/**
 * Shared token resolution helper for the Codex CLI / ChatGPT Plus provider.
 * Extracts and caches credentials across agent reasoning and routing factories.
 */
@ApplicationScoped
public class CodexTokenResolver {

    private static final Logger log = Logger.getLogger(CodexTokenResolver.class);

    @Inject
    CodexOAuthTokenExtractor tokenExtractor;

    @Inject
    SecretVault secretVault;

    /**
     * Resolves the Codex token with no explicit key override.
     */
    public String resolveToken() {
        return resolveToken(Optional.empty());
    }

    /**
     * Resolves the Codex token, checking an optional explicit key, then vault,
     * then OAuth extractor, and finally system/environment variables.
     */
    public String resolveToken(Optional<String> explicitApiKey) {
        if (explicitApiKey != null
                && explicitApiKey.isPresent()
                && !explicitApiKey.get().isBlank()) {
            return explicitApiKey.get();
        }

        if (secretVault != null) {
            Optional<String> vaultToken = secretVault
                    .retrieveSystemKey(VaultConstants.CODEX_OAUTH_TOKEN_KEY)
                    .map(VaultKey::get)
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .filter(k -> !k.isBlank());
            if (vaultToken.isPresent()) {
                return vaultToken.get();
            }
        }

        if (tokenExtractor != null) {
            Optional<String> extracted = tokenExtractor.extractToken();
            if (extracted.isPresent()) {
                if (secretVault != null) {
                    secretVault.storeSystemKey(
                            VaultConstants.CODEX_OAUTH_TOKEN_KEY,
                            extracted.get().getBytes(StandardCharsets.UTF_8));
                    log.info("Codex provider: token loaded from auth.json and stored in vault.");
                }
                return extracted.get();
            }
        }

        return Optional.ofNullable(System.getProperty("AMSHA_CODEX_API_KEY"))
                .filter(k -> !k.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty("amsha.llm.codex.api-key"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_CODEX_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .orElseThrow(() -> new IllegalStateException("Codex provider requires an active login. "
                        + "Please run 'codex login' in your local terminal first, or set AMSHA_CODEX_API_KEY."));
    }

    /**
     * Resolves the Codex account_id from the token extractor if present.
     */
    public Optional<String> resolveAccountId() {
        if (tokenExtractor != null) {
            return tokenExtractor.extractAccountId();
        }
        return Optional.empty();
    }
}
