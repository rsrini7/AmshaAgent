package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.WorkspaceConnectionGrantDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelProfileCredentialResolverTest {

    @Mock
    SecretVault vault;

    @Mock
    LlmProfileStorePort profileStore;

    @Mock
    WorkspaceConnectionGrantStorePort grantStore;

    @Mock
    ActiveWorkspaceContext activeWorkspaceContext;

    @InjectMocks
    ModelProfileCredentialResolver resolver;

    private static final Instant NOW = Instant.now();
    private static final String OWNER = "user-1";
    private static final String WS = "ws-1";

    private LlmConnectionDto userConn() {
        return new LlmConnectionDto(
                "conn-u", "USER", OWNER, OWNER, "conn", "openai", null, null, "api_key", null, "ACTIVE", 1, NOW, NOW);
    }

    private LlmConnectionDto wsConn() {
        return new LlmConnectionDto(
                "conn-w", "WORKSPACE", WS, OWNER, "conn", "groq", null, null, "api_key", null, "ACTIVE", 1, NOW, NOW);
    }

    private LlmConnectionDto systemConn() {
        return new LlmConnectionDto(
                "conn-s", "SYSTEM", "SYSTEM", "system", "conn", "openai", null, null, "api_key", null, "ACTIVE", 1, NOW,
                NOW);
    }

    private LlmConnectionSecretRefDto ref(String connId, String vaultRef) {
        return new LlmConnectionSecretRefDto(connId, "slot-1", vaultRef, 1, NOW, NOW);
    }

    private VaultKey key(String value) {
        return new VaultKey(value.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());
    }

    @Test
    void userConnection_resolvesFromUserVaultPartition() {
        LlmConnectionDto conn = userConn();
        when(profileStore.listSecretRefs("conn-u")).thenReturn(List.of(ref("conn-u", "vault:u:openai")));
        when(vault.get("vault:u:openai", OWNER)).thenReturn(Optional.of(key("sk-user")));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);

        assertTrue(cred.isPresent());
        assertEquals("openai", cred.get().providerAdapterId());
        assertEquals("https://api.openai.com/v1", cred.get().baseUrl());
        assertEquals("sk-user", cred.get().apiKey());
    }

    @Test
    void systemConnection_resolvesFromSystemVaultPartition() {
        LlmConnectionDto conn = systemConn();
        when(profileStore.listSecretRefs("conn-s")).thenReturn(List.of(ref("conn-s", "LLM_API_KEY")));
        when(vault.retrieveSystemKey("LLM_API_KEY")).thenReturn(Optional.of(key("sk-system")));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);

        assertTrue(cred.isPresent());
        assertEquals("sk-system", cred.get().apiKey());
    }

    @Test
    void workspaceConnection_withActiveGrantAndActiveWorkspace_resolves() {
        LlmConnectionDto conn = wsConn();
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.of(WS));
        when(grantStore.findByWorkspaceAndConnection(eq(WS), eq("conn-w"))).thenReturn(Optional.of(activeGrant()));
        when(profileStore.listSecretRefs("conn-w")).thenReturn(List.of(ref("conn-w", "vault:ws:groq")));
        when(vault.getWorkspace("vault:ws:groq", WS, OWNER)).thenReturn(Optional.of(key("sk-ws")));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);

        assertTrue(cred.isPresent());
        assertEquals("groq", cred.get().providerAdapterId());
        assertEquals("https://api.groq.com/openai/v1", cred.get().baseUrl());
        assertEquals("sk-ws", cred.get().apiKey());
    }

    @Test
    void workspaceConnection_withoutGrantOrActiveWorkspace_returnsEmpty() {
        LlmConnectionDto conn = wsConn();
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());
        when(grantStore.findByWorkspaceAndConnection(eq(WS), anyString())).thenReturn(Optional.empty());
        when(profileStore.listSecretRefs("conn-w")).thenReturn(List.of(ref("conn-w", "vault:ws:groq")));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);

        assertFalse(cred.isPresent());
    }

    @Test
    void workspaceConnection_withGrantButNotInActiveWorkspace_returnsEmpty() {
        LlmConnectionDto conn = wsConn();
        // Actor is not currently in the granted workspace.
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());
        when(grantStore.findByWorkspaceAndConnection(eq(WS), eq("conn-w"))).thenReturn(Optional.of(activeGrant()));
        when(profileStore.listSecretRefs("conn-w")).thenReturn(List.of(ref("conn-w", "vault:ws:groq")));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);

        // Grant alone is insufficient; the actor must be in the workspace at resolution time.
        assertFalse(cred.isPresent());
    }

    @Test
    void workspaceConnection_withInactiveGrant_returnsEmpty() {
        LlmConnectionDto conn = wsConn();
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.of(WS));
        WorkspaceConnectionGrantDto inactive = new WorkspaceConnectionGrantDto(
                WS, "conn-w", OWNER, "MEMBER_USE", "REVOKED", Instant.now(), Instant.now());
        when(grantStore.findByWorkspaceAndConnection(eq(WS), eq("conn-w"))).thenReturn(Optional.of(inactive));
        when(profileStore.listSecretRefs("conn-w")).thenReturn(List.of(ref("conn-w", "vault:ws:groq")));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);

        // A non-ACTIVE grant must not authorize use (grant bypass prevented).
        assertFalse(cred.isPresent());
    }

    private WorkspaceConnectionGrantDto activeGrant() {
        return new WorkspaceConnectionGrantDto(
                WS, "conn-w", OWNER, "MEMBER_USE", "ACTIVE", Instant.now(), Instant.now());
    }

    @Test
    void noSecretRefs_returnsEmpty() {
        when(profileStore.listSecretRefs("conn-u")).thenReturn(List.of());
        assertFalse(resolver.resolve(userConn(), OWNER).isPresent());
    }

    @Test
    void missingVaultKey_returnsEmpty() {
        LlmConnectionDto conn = userConn();
        when(profileStore.listSecretRefs("conn-u")).thenReturn(List.of(ref("conn-u", "vault:u:openai")));
        when(vault.get("vault:u:openai", OWNER)).thenReturn(Optional.empty());
        assertFalse(resolver.resolve(conn, OWNER).isPresent());
    }

    @Test
    void credentialIsClosableAndZeroesKeyOnClose() {
        LlmConnectionDto conn = userConn();
        when(profileStore.listSecretRefs("conn-u")).thenReturn(List.of(ref("conn-u", "vault:u:openai")));
        VaultKey vk = key("sk-secret");
        when(vault.get("vault:u:openai", OWNER)).thenReturn(Optional.of(vk));

        Optional<ResolvedCredential> cred = resolver.resolve(conn, OWNER);
        assertTrue(cred.isPresent());
        // VaultKey is closed (destroyed) by the resolver after reading.
        assertTrue(vk.isDestroyed(), "vault key must be closed after resolution");
        // ResolvedCredential must remain usable (it cloned the bytes).
        assertEquals("sk-secret", cred.get().apiKey());
        cred.get().close();
    }
}
