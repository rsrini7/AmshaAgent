package com.amsha.llm.providers.helper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.OutboundApprovalReceipt;

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

    @Inject
    Instance<ApprovalReceiptIssuer> approvalReceiptIssuerInstance;

    ApprovalReceiptIssuer approvalReceiptIssuer;

    ApprovalReceiptIssuer resolveIssuer() {
        if (approvalReceiptIssuer != null) return approvalReceiptIssuer;
        if (approvalReceiptIssuerInstance != null && approvalReceiptIssuerInstance.isResolvable()) {
            return approvalReceiptIssuerInstance.get();
        }
        return null;
    }

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

        return Optional.ofNullable(System.getProperty(ConfigKeys.KIMCHI_API_KEY))
                .filter(k -> !k.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty(LlmConfigKeys.KIMCHI_API_KEY))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv(ConfigKeys.KIMCHI_API_KEY))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("KIMCHI_API_KEY")).filter(k -> !k.isBlank()))
                .orElseThrow(() -> new IllegalStateException("Kimchi provider requires active credentials. "
                        + "Please run 'kimchi' CLI in your terminal or set AMSHA_KIMCHI_API_KEY."));
    }

    /**
     * Resolves the Kimchi token using a checkpoint-backed operation grant.
     * Verifies the single-use grant for actor binding, burns the grant, extracts the token,
     * and stores it securely into the secret vault.
     */
    public String resolveTokenWithGrant(String actorUserId, String sessionId, String approvalToken) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalStateException("Actor user ID is required to resolve Kimchi credentials");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Session ID is required to resolve Kimchi credentials");
        }
        if (approvalToken == null || approvalToken.isBlank()) {
            throw new IllegalStateException("Operation grant approvalToken is required to resolve Kimchi credentials");
        }
        ApprovalReceiptIssuer issuer = resolveIssuer();
        if (issuer == null) {
            throw new IllegalStateException("ApprovalReceiptIssuer is unavailable");
        }
        OutboundApprovalReceipt receipt =
                issuer.verifyAndConsume(approvalToken, OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, "llm.kimchi.dev");
        if (receipt == null) {
            throw new IllegalStateException("Operation grant is invalid, expired, or already consumed");
        }
        if (!actorUserId.equals(receipt.approvedByUserId())) {
            throw new IllegalStateException("Operation grant actor mismatch");
        }
        if (!ApprovalReceiptIssuer.oauthExchangeSessionId(ModelDefaults.PROVIDER_KIMCHI, sessionId)
                .equals(receipt.sessionId())) {
            throw new IllegalStateException("Operation grant session mismatch");
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
                        log.debug("Kimchi provider: token loaded via operation grant and stored in vault.");
                    } finally {
                        Arrays.fill(tokenBytes, (byte) 0);
                    }
                }
                return extracted.get();
            }
        }

        throw new IllegalStateException("Kimchi credentials not found after grant verification");
    }
}
