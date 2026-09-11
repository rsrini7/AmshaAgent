package com.amsha.llm.providers.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.audit.AuditService;
import com.amsha.llm.api.oauth.CodexOAuthTokenExtractor;
import com.amsha.llm.providers.helper.CodexTokenResolver;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;
import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundApprovalReceipt;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of the grant → exchange → vault → provider chain using production
 * classes (real {@link ApprovalReceiptIssuer}, real {@link ProviderOAuthExchangeService} grant
 * consumption through the governed transport, and real {@link CodexTokenResolver} vault reads).
 * Only the HTTP transport and external token extractor are stubbed.
 */
class ProviderOAuthGrantFlowTest {

    private static final String TOKEN_ENDPOINT = "https://auth0.openai.com/oauth/token";
    private static final String TOKEN_HOST = "auth0.openai.com";

    @Test
    void grantToExchangeToVaultToProvider_endToEnd() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        InMemoryVault vault = new InMemoryVault();
        GovernedTransportService transport = mock(GovernedTransportService.class);
        AuditService audit = mock(AuditService.class);

        @SuppressWarnings("unchecked")
        HttpResponse<String> tokenResponse = mock(HttpResponse.class);
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"access_token\":\"e2e-access-token\",\"expires_in\":3600}");

        when(transport.<String>send(anyString(), any(), any(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String approvalToken = invocation.getArgument(5);
                    String url = invocation.getArgument(0);
                    OutboundApprovalReceipt receipt = issuer.verifyAndConsume(
                            approvalToken,
                            OutboundPurpose.PROVIDER_OAUTH_EXCHANGE,
                            URI.create(url).getHost());
                    if (receipt == null) {
                        throw new OutboundHttpException(OutboundHttpException.Category.HITL_REQUIRED, "invalid grant");
                    }
                    if (!"user-1".equals(receipt.approvedByUserId())) {
                        throw new OutboundHttpException(OutboundHttpException.Category.HITL_REQUIRED, "actor mismatch");
                    }
                    return tokenResponse;
                });

        ProviderOAuthExchangeService service = new ProviderOAuthExchangeService();
        service.governedTransport = transport;
        service.secretVault = vault;
        service.auditService = audit;
        service.approvalReceiptIssuer = issuer;
        service.pkceStore = new ProviderOAuthPkceStore();
        service.objectMapper = new ObjectMapper();

        String grant = issuer.issueForOAuthExchange("user-1", "sess-1", "codex", TOKEN_HOST);

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex", TOKEN_ENDPOINT, Map.of("grant_type", "refresh_token"), "user-1", "sess-1", grant);

        assertTrue(result.success());
        assertTrue(vault.systemKeyExists("codex-oauth-token"));

        CodexTokenResolver resolver = new CodexTokenResolver();
        inject(resolver, "secretVault", vault);
        CodexOAuthTokenExtractor extractor = mock(CodexOAuthTokenExtractor.class);
        when(extractor.extractToken()).thenReturn(Optional.empty());
        inject(resolver, "tokenExtractor", extractor);
        inject(resolver, "approvalReceiptIssuer", issuer);

        assertEquals("e2e-access-token", resolver.resolveToken(Optional.empty()));

        ProviderOAuthResult reuse = service.exchangeOrRefreshToken(
                "codex", TOKEN_ENDPOINT, Map.of("grant_type", "refresh_token"), "user-1", "sess-1", grant);
        assertFalse(reuse.success());
        assertEquals("e2e-access-token", new String(vault.stored.get("codex-oauth-token"), StandardCharsets.UTF_8));
    }

    @Test
    void exchangeWithMismatchedActorGrant_fails() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        InMemoryVault vault = new InMemoryVault();
        GovernedTransportService transport = mock(GovernedTransportService.class);

        when(transport.<String>send(
                        anyString(),
                        eq(OutboundPurpose.PROVIDER_OAUTH_EXCHANGE),
                        any(),
                        anyString(),
                        eq(false),
                        anyString(),
                        any(),
                        any()))
                .thenThrow(new OutboundHttpException(OutboundHttpException.Category.HITL_REQUIRED, "actor mismatch"));

        ProviderOAuthExchangeService service = new ProviderOAuthExchangeService();
        service.governedTransport = transport;
        service.secretVault = vault;
        service.auditService = mock(AuditService.class);
        service.approvalReceiptIssuer = issuer;
        service.pkceStore = new ProviderOAuthPkceStore();
        service.objectMapper = new ObjectMapper();

        String grant = issuer.issueForOAuthExchange("user-1", "sess-1", "codex", TOKEN_HOST);

        ProviderOAuthResult result = service.exchangeOrRefreshToken(
                "codex", TOKEN_ENDPOINT, Map.of("grant_type", "refresh_token"), "intruder", "sess-1", grant);

        assertFalse(result.success());
        assertFalse(vault.systemKeyExists("codex-oauth-token"));
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Test wiring failed for field " + field, e);
        }
    }

    /** Minimal in-memory {@link SecretVault} for flow verification. */
    static final class InMemoryVault implements SecretVault {
        final ConcurrentHashMap<String, byte[]> stored = new ConcurrentHashMap<>();

        @Override
        public void init(String sessionId) {}

        @Override
        public void store(String keyName, byte[] secret) {
            storeSystemKey(keyName, secret);
        }

        @Override
        public void store(String keyName, byte[] secret, String userId) {
            storeSystemKey(keyName, secret);
        }

        @Override
        public Optional<VaultKey> get(String keyName) {
            return retrieveSystemKey(keyName);
        }

        @Override
        public Optional<VaultKey> get(String keyName, String userId) {
            return retrieveSystemKey(keyName);
        }

        @Override
        public void remove(String keyName, String userId) {
            stored.remove(keyName);
        }

        @Override
        public void storeWorkspace(String keyName, byte[] secret, String workspaceId, String actorUserId) {
            storeSystemKey(keyName, secret);
        }

        @Override
        public Optional<VaultKey> getWorkspace(String keyName, String workspaceId, String actorUserId) {
            return retrieveSystemKey(keyName);
        }

        @Override
        public void removeWorkspace(String keyName, String workspaceId, String actorUserId) {
            stored.remove(keyName);
        }

        @Override
        public Optional<VaultKey> retrieveSystemKey(String keyName) {
            byte[] bytes = stored.get(keyName);
            if (bytes == null) {
                return Optional.empty();
            }
            return Optional.of(new VaultKey(bytes.clone()));
        }

        @Override
        public boolean systemKeyExists(String keyName) {
            return stored.containsKey(keyName);
        }

        @Override
        public void storeSystemKey(String keyName, byte[] secret) {
            stored.put(keyName, secret.clone());
        }

        @Override
        public void removeSystemKey(String keyName) {
            stored.remove(keyName);
        }
    }
}
