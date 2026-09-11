package com.amsha.llm.providers.oauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundApprovalReceipt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Mediates provider OAuth 2.0 token exchange and refresh operations through
 * {@link GovernedTransportService} backed by checkpoint operation grants.
 * Enforces TLS, actor binding, and strict zero-leakage credential protection.
 */
@ApplicationScoped
@Unremovable
public class ProviderOAuthExchangeService {

    private static final Logger LOG = Logger.getLogger(ProviderOAuthExchangeService.class);
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    Instance<ProviderOAuthPkceStore> pkceStoreInstance;

    @Inject
    Instance<GovernedTransportService> governedTransportInstance;

    @Inject
    Instance<SecretVault> secretVaultInstance;

    @Inject
    Instance<AuditService> auditServiceInstance;

    @Inject
    Instance<ObjectMapper> objectMapperInstance;

    @Inject
    Instance<ApprovalReceiptIssuer> approvalReceiptIssuerInstance;

    GovernedTransportService governedTransport;
    SecretVault secretVault;
    AuditService auditService;
    ObjectMapper objectMapper;
    ApprovalReceiptIssuer approvalReceiptIssuer;
    ProviderOAuthPkceStore pkceStore;

    GovernedTransportService resolveTransport() {
        if (governedTransport != null) return governedTransport;
        if (governedTransportInstance != null && governedTransportInstance.isResolvable()) {
            return governedTransportInstance.get();
        }
        return null;
    }

    SecretVault resolveVault() {
        if (secretVault != null) return secretVault;
        if (secretVaultInstance != null && secretVaultInstance.isResolvable()) {
            return secretVaultInstance.get();
        }
        return null;
    }

    AuditService resolveAudit() {
        if (auditService != null) return auditService;
        if (auditServiceInstance != null && auditServiceInstance.isResolvable()) {
            return auditServiceInstance.get();
        }
        return null;
    }

    ObjectMapper resolveMapper() {
        if (objectMapper != null) return objectMapper;
        if (objectMapperInstance != null && objectMapperInstance.isResolvable()) {
            return objectMapperInstance.get();
        }
        return new ObjectMapper();
    }

    ApprovalReceiptIssuer resolveIssuer() {
        if (approvalReceiptIssuer != null) return approvalReceiptIssuer;
        if (approvalReceiptIssuerInstance != null && approvalReceiptIssuerInstance.isResolvable()) {
            return approvalReceiptIssuerInstance.get();
        }
        return null;
    }

    ProviderOAuthPkceStore resolvePkceStore() {
        if (pkceStore != null) return pkceStore;
        if (pkceStoreInstance != null && pkceStoreInstance.isResolvable()) {
            pkceStore = pkceStoreInstance.get();
            return pkceStore;
        }
        pkceStore = new ProviderOAuthPkceStore();
        return pkceStore;
    }

    /**
     * Executes a governed OAuth token exchange or refresh, validating the single-use operation grant
     * before establishing connection and storing the received credential securely in the vault.
     * Uses the default (connection-agnostic) network policy. Authorization-code grants without a
     * PKCE verifier are rejected; use the full overload with {@code codeVerifier} and {@code oauthState}
     * for authorization-code flows.
     */
    public ProviderOAuthResult exchangeOrRefreshToken(
            String providerId,
            String tokenEndpoint,
            Map<String, String> formParameters,
            String actorUserId,
            String sessionId,
            String approvalToken) {
        return exchangeOrRefreshToken(
                providerId, tokenEndpoint, formParameters, null, actorUserId, sessionId, approvalToken, null, null);
    }

    /**
     * Executes a governed OAuth token exchange or refresh with an optional per-connection network
     * profile and PKCE binding. When {@code connection} is supplied its {@code networkProfileJson}
     * proxy/allow-list policy is enforced by the governed transport; otherwise the default policy
     * (SSRF/TLS/DNS-rebinding validation) applies. Authorization-code exchanges must supply the
     * {@code codeVerifier} and {@code oauthState} from {@link #beginAuthorization}; refresh-token
     * exchanges omit both.
     */
    public ProviderOAuthResult exchangeOrRefreshToken(
            String providerId,
            String tokenEndpoint,
            Map<String, String> formParameters,
            LlmConnectionDto connection,
            String actorUserId,
            String sessionId,
            String approvalToken,
            String codeVerifier,
            String oauthState) {
        if (providerId == null || providerId.isBlank()) {
            return ProviderOAuthResult.failure("Provider ID is required");
        }
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            return ProviderOAuthResult.failure("Token endpoint is required");
        }
        if (actorUserId == null || actorUserId.isBlank()) {
            return ProviderOAuthResult.failure("Actor user ID is required");
        }
        if (approvalToken == null || approvalToken.isBlank()) {
            return ProviderOAuthResult.failure("Operation grant approvalToken is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return ProviderOAuthResult.failure("Session ID is required for OAuth exchange");
        }

        ProviderOAuthPkceStore pkce = resolvePkceStore();
        Optional<String> pkceError =
                pkce.claimForExchange(providerId, formParameters, actorUserId, sessionId, codeVerifier, oauthState);
        if (pkceError.isPresent()) {
            recordAuditEvent(
                    sessionId,
                    actorUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "oauth_pkce_rejected provider=" + providerId);
            return ProviderOAuthResult.failure(pkceError.get());
        }

        Map<String, String> requestParameters =
                formParameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(formParameters);
        if (ProviderOAuthPkceStore.isAuthorizationCodeFlow(formParameters)) {
            String suppliedVerifier = requestParameters.get("code_verifier");
            if (suppliedVerifier != null && !suppliedVerifier.equals(codeVerifier)) {
                pkce.release(oauthState);
                recordAuditEvent(
                        sessionId,
                        actorUserId,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "oauth_pkce_rejected provider=" + providerId);
                return ProviderOAuthResult.failure("PKCE code_verifier does not match validated verifier");
            }
            requestParameters.put("code_verifier", codeVerifier);
        }

        GovernedTransportService transport = resolveTransport();
        if (transport == null) {
            pkce.release(oauthState);
            return ProviderOAuthResult.failure("GovernedTransportService unavailable");
        }

        String effectiveSession = sessionId;
        String formBody = requestParameters.isEmpty()
                ? ""
                : requestParameters.entrySet().stream()
                        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .timeout(EXCHANGE_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = transport.send(
                    tokenEndpoint,
                    OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                    connection,
                    actorUserId,
                    false,
                    approvalToken,
                    request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                pkce.release(oauthState);
                LOG.warnf("OAuth token exchange failed for provider %s: HTTP %d", providerId, response.statusCode());
                recordAuditEvent(
                        effectiveSession,
                        actorUserId,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "oauth_exchange_failed provider=" + providerId + " status=" + response.statusCode());
                return ProviderOAuthResult.failure("Token exchange failed: HTTP " + response.statusCode());
            }

            ProviderOAuthResult result =
                    processTokenResponse(providerId, response.body(), effectiveSession, actorUserId);
            if (result.success()) {
                resolvePkceStore().consume(oauthState);
            } else {
                resolvePkceStore().release(oauthState);
            }
            return result;
        } catch (Exception e) {
            resolvePkceStore().release(oauthState);
            LOG.warnf("Exception during OAuth exchange for provider %s: %s", providerId, LogUtil.rootMessage(e));
            recordAuditEvent(
                    effectiveSession,
                    actorUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "oauth_exchange_error provider=" + providerId);
            return ProviderOAuthResult.failure("OAuth exchange failed");
        }
    }

    private ProviderOAuthResult processTokenResponse(
            String providerId, String responseBody, String sessionId, String actorUserId) {
        if (responseBody == null || responseBody.isBlank()) {
            return ProviderOAuthResult.failure("Empty token response");
        }

        try {
            JsonNode root = resolveMapper().readTree(responseBody);
            String accessToken = extractJsonField(root, "access_token", "accessToken");
            if (accessToken == null || accessToken.isBlank()) {
                return ProviderOAuthResult.failure("Missing access_token in response");
            }

            String refreshToken = extractJsonField(root, "refresh_token", "refreshToken");
            Integer expiresIn = null;
            if (root.hasNonNull("expires_in")) {
                int val = root.get("expires_in").asInt();
                if (val > 0) {
                    expiresIn = val;
                }
            } else if (root.hasNonNull("expiresIn")) {
                int val = root.get("expiresIn").asInt();
                if (val > 0) {
                    expiresIn = val;
                }
            }

            SecretVault vault = resolveVault();
            if (vault != null) {
                String vaultKey = resolveVaultKey(providerId);
                byte[] accessBytes = accessToken.getBytes(StandardCharsets.UTF_8);
                try {
                    vault.storeSystemKey(vaultKey, accessBytes);
                    if (refreshToken != null && !refreshToken.isBlank()) {
                        byte[] refreshBytes = refreshToken.getBytes(StandardCharsets.UTF_8);
                        try {
                            vault.storeSystemKey(vaultKey + "-refresh", refreshBytes);
                        } finally {
                            Arrays.fill(refreshBytes, (byte) 0);
                        }
                    }
                } finally {
                    Arrays.fill(accessBytes, (byte) 0);
                }
            }

            recordAuditEvent(
                    sessionId,
                    actorUserId,
                    SecurityEventType.SETTINGS_CHANGED,
                    "oauth_exchange_succeeded provider=" + providerId);

            return ProviderOAuthResult.success(expiresIn);
        } catch (Exception e) {
            LOG.warnf("Failed to parse OAuth response for %s: %s", providerId, LogUtil.rootMessage(e));
            return ProviderOAuthResult.failure("Failed to parse OAuth token response");
        }
    }

    /**
     * Stores an extracted credential into the vault only after verifying and consuming
     * a valid single-use operation grant bound to the actor.
     */
    public boolean storeExtractedTokenWithGrant(
            String providerId, String rawToken, String actorUserId, String sessionId, String approvalToken) {
        if (providerId == null
                || providerId.isBlank()
                || rawToken == null
                || rawToken.isBlank()
                || actorUserId == null
                || actorUserId.isBlank()
                || sessionId == null
                || sessionId.isBlank()
                || approvalToken == null
                || approvalToken.isBlank()) {
            return false;
        }

        SecretVault vault = resolveVault();
        if (vault == null) {
            return false;
        }

        ApprovalReceiptIssuer issuer = resolveIssuer();
        if (issuer == null) {
            return false;
        }

        String targetHost = resolveTargetHost(providerId);
        OutboundApprovalReceipt receipt =
                issuer.verifyAndConsume(approvalToken, OutboundPurpose.PROVIDER_OAUTH_EXCHANGE, targetHost);
        if (receipt == null
                || !actorUserId.equals(receipt.approvedByUserId())
                || !ApprovalReceiptIssuer.oauthExchangeSessionId(providerId, sessionId)
                        .equals(receipt.sessionId())) {
            return false;
        }

        String vaultKey = resolveVaultKey(providerId);
        byte[] tokenBytes = rawToken.getBytes(StandardCharsets.UTF_8);
        try {
            vault.storeSystemKey(vaultKey, tokenBytes);
            recordAuditEvent(
                    sessionId,
                    actorUserId,
                    SecurityEventType.SETTINGS_CHANGED,
                    "oauth_token_stored_with_grant provider=" + providerId);
            return true;
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
        }
    }

    public static String resolveVaultKey(String providerId) {
        if (providerId == null) return "generic-oauth-token";
        String norm = providerId.trim().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "codex" -> VaultConstants.CODEX_OAUTH_TOKEN_KEY;
            case "claude-code", "claude_code" -> VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY;
            case "kimchi" -> VaultConstants.KIMCHI_OAUTH_TOKEN_KEY;
            case "agy", "antigravity" -> VaultConstants.AGY_OAUTH_TOKEN_KEY;
            default -> norm + "-oauth-token";
        };
    }

    public static String resolveTargetHost(String providerId) {
        if (providerId == null) return "localhost";
        String norm = providerId.trim().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "codex" -> "chatgpt.com";
            case "claude-code", "claude_code" -> "api.anthropic.com";
            case "agy", "antigravity" -> "generativelanguage.googleapis.com";
            case "kimchi" -> "llm.kimchi.dev";
            default -> "localhost";
        };
    }

    public record PkceParameters(String codeVerifier, String codeChallenge, String state) {}

    /**
     * Begins a PKCE authorization flow by generating verifier/challenge/state and storing the
     * challenge bound to provider, actor, and session. Delegates storage to
     * {@link ProviderOAuthPkceStore}; the verifier is returned to the caller and never persisted.
     */
    public PkceParameters beginAuthorization(String providerId, String actorUserId, String sessionId) {
        PkceParameters params = resolvePkceStore().beginAuthorization(providerId, actorUserId, sessionId);
        recordAuditEvent(
                sessionId,
                actorUserId,
                SecurityEventType.SETTINGS_CHANGED,
                "oauth_authorization_started provider=" + providerId);
        return params;
    }

    /**
     * Validates an OAuth callback state against a pending PKCE authorization without consuming it.
     * The pending entry is consumed only by a successful authorization-code token exchange.
     */
    public boolean validateCallbackState(String providerId, String state, String actorUserId, String sessionId) {
        return resolvePkceStore().validateCallbackState(providerId, state, actorUserId, sessionId);
    }

    public static PkceParameters generatePkceParams() {
        return ProviderOAuthPkceStore.generatePkceParams();
    }

    static String computeS256Challenge(String verifier) {
        return ProviderOAuthPkceStore.computeS256Challenge(verifier);
    }

    private String extractJsonField(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            if (node.hasNonNull(field)) {
                String val = node.get(field).asText();
                if (!val.isBlank()) return val;
            }
        }
        return null;
    }

    private void recordAuditEvent(String sessionId, String actorUserId, SecurityEventType eventType, String detail) {
        AuditService audit = resolveAudit();
        if (audit != null) {
            audit.recordMandatory(
                    sessionId != null ? sessionId : "system",
                    actorUserId != null ? actorUserId : "system",
                    "system",
                    eventType,
                    detail);
        }
    }
}
