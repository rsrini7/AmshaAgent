package com.amsha.llm.providers.helper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Shared token resolution helper for the Kimchi (kimchi.dev) CLI provider.
 * Extracts and caches credentials across agent reasoning and routing factories.
 */
@ApplicationScoped
@Unremovable
public class KimchiTokenResolver {

    private static final Logger log = Logger.getLogger(KimchiTokenResolver.class);

    @Inject
    KimchiOAuthTokenExtractor tokenExtractor;

    @Inject
    SecretVault secretVault;

    /**
     * Resolves the Kimchi token with no explicit key override.
     */
    public String resolveToken() {
        return resolveToken(Optional.empty());
    }

    /**
     * Resolves the Kimchi token, checking an optional explicit key, then vault,
     * then OAuth extractor, and finally system/environment variables.
     */
    public String resolveToken(Optional<String> explicitApiKey) {
        if (explicitApiKey != null
                && explicitApiKey.isPresent()
                && !explicitApiKey.get().isBlank()) {
            return explicitApiKey.get();
        }

        if (secretVault != null) {
            Optional<VaultKey> kimchiKey = secretVault.retrieveSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY);
            if (kimchiKey.isPresent()) {
                try (VaultKey keyHandle = kimchiKey.get()) {
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
                        secretVault.storeSystemKey(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY, tokenBytes);
                        log.info("Kimchi provider: token loaded and cached in vault.");
                    } finally {
                        Arrays.fill(tokenBytes, (byte) 0);
                    }
                }
                return extracted.get();
            }
        }

        return Optional.ofNullable(System.getProperty("AMSHA_KIMCHI_API_KEY"))
                .filter(k -> !k.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty("amsha.llm.kimchi.api-key"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_KIMCHI_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("KIMCHI_API_KEY")).filter(k -> !k.isBlank()))
                .orElseThrow(() -> new IllegalStateException("Kimchi provider requires active credentials. "
                        + "Please run 'kimchi' CLI in your terminal or set AMSHA_KIMCHI_API_KEY."));
    }
}
