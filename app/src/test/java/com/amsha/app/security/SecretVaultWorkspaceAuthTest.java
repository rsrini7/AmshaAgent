package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.SecretVaultImpl;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies the owner/admin authorization gate for rotation/deletion of shared
 * workspace secrets (Finding 2 remediation). Members may read/use; only
 * OWNER/ADMIN roles with an ACTIVE membership may store/remove a workspace key.
 */
@QuarkusTest
@SuppressWarnings("java:S1192")
class SecretVaultWorkspaceAuthTest {

    @Inject
    SecretVaultImpl vault;

    @InjectMock
    IdentityStorePort identityStore;

    private static final String WS = "ws-auth";
    private static final String KEY = "vault:ws:groq";
    private static final byte[] SECRET = "sk-workspace".getBytes(StandardCharsets.UTF_8);

    private WorkspaceMembershipDto membership(String role, String status) {
        return new WorkspaceMembershipDto(WS, "actor", role, status, 1, Instant.now(), Instant.now());
    }

    private void run(String userId, Runnable r) {
        WorkspaceContext.runWithFullAutonomy("sess", userId, "chat", "role", r);
    }

    @Test
    void memberCannotRemoveWorkspaceSecret() {
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.of(membership("MEMBER", "ACTIVE")));
        run("actor", () -> assertThrows(SecurityException.class, () -> vault.removeWorkspace(KEY, WS, "actor")));
    }

    @Test
    void inactiveOwnerCannotStoreWorkspaceSecret() {
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.of(membership("OWNER", "INVITED")));
        run("actor", () -> assertThrows(SecurityException.class, () -> vault.storeWorkspace(KEY, SECRET, WS, "actor")));
    }

    @Test
    void nonMemberCannotStoreWorkspaceSecret() {
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.empty());
        run("actor", () -> assertThrows(SecurityException.class, () -> vault.storeWorkspace(KEY, SECRET, WS, "actor")));
    }

    @Test
    void ownerCanUseWorkspaceSecret() {
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.of(membership("OWNER", "ACTIVE")));
        run("actor", () -> {
            vault.storeWorkspace(KEY, SECRET, WS, "actor");
            Optional<VaultKey> key = vault.getWorkspace(KEY, WS, "actor");
            assertTrue(key.isPresent());
            key.ifPresent(VaultKey::close);
        });
    }

    @Test
    void adminCanRemoveWorkspaceSecret() {
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.of(membership("ADMIN", "ACTIVE")));
        run("actor", () -> {
            vault.storeWorkspace(KEY, SECRET, WS, "actor");
            vault.removeWorkspace(KEY, WS, "actor");
        });
    }

    @Test
    void memberCanReadButNotRotate() {
        // Owner seeds the shared secret.
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.of(membership("OWNER", "ACTIVE")));
        run("actor", () -> vault.storeWorkspace(KEY, SECRET, WS, "actor"));

        // Member may read but may not rotate (store) the shared secret.
        Mockito.when(identityStore.findMembership(WS, "actor")).thenReturn(Optional.of(membership("MEMBER", "ACTIVE")));
        run("actor", () -> {
            assertTrue(
                    vault.getWorkspace(KEY, WS, "actor").isPresent(),
                    "Members may read (use) the shared workspace secret");
            assertThrows(
                    SecurityException.class,
                    () -> vault.storeWorkspace(KEY + "-2", SECRET, WS, "actor"),
                    "Rotation is not allowed for members");
        });
    }
}
