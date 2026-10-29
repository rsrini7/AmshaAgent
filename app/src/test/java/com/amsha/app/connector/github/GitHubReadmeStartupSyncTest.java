package com.amsha.app.connector.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubReadmeStartupSyncTest {

    private GitHubReadmeConnectorService connectorService;
    private IdentityStorePort identityStore;
    private SecretVault secretVault;

    @BeforeEach
    void setUp() {
        connectorService = mock(GitHubReadmeConnectorService.class);
        identityStore = mock(IdentityStorePort.class);
        secretVault = mock(SecretVault.class);
    }

    @Test
    void whenConnectorDisabledDoesNothing() {
        GitHubReadmeStartupSync sync =
                new GitHubReadmeStartupSync(connectorService, identityStore, secretVault, false, true);

        sync.syncAllWorkspaces();

        verify(secretVault, never()).systemKeyExists(any());
        verify(identityStore, never()).listWorkspaces();
        verify(connectorService, never()).connect(any(), any(), any());
        verify(connectorService, never()).refresh(any(), any());
    }

    @Test
    void whenCredentialMissingInSecretVaultDoesNotAttemptSync() {
        GitHubReadmeStartupSync sync =
                new GitHubReadmeStartupSync(connectorService, identityStore, secretVault, true, true);
        when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)).thenReturn(false);

        sync.syncAllWorkspaces();

        verify(secretVault).systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY);
        verify(identityStore, never()).listWorkspaces();
        verify(connectorService, never()).connect(any(), any(), any());
    }

    @Test
    void whenNoWorkspacesExistDoesNothing() {
        GitHubReadmeStartupSync sync =
                new GitHubReadmeStartupSync(connectorService, identityStore, secretVault, true, true);
        when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)).thenReturn(true);
        when(identityStore.listWorkspaces()).thenReturn(List.of());

        sync.syncAllWorkspaces();

        verify(identityStore).listWorkspaces();
        verify(connectorService, never()).connect(any(), any(), any());
    }

    @Test
    void whenWorkspaceNotYetIndexedCallsConnect() {
        GitHubReadmeStartupSync sync =
                new GitHubReadmeStartupSync(connectorService, identityStore, secretVault, true, true);
        when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)).thenReturn(true);
        Instant now = Instant.now();
        when(identityStore.listWorkspaces())
                .thenReturn(List.of(new IdentityWorkspaceDto("ws-1", "Default", "owner-1", now, now)));
        when(connectorService.status("owner-1", "ws-1"))
                .thenReturn(new GitHubReadmeStatusResponse(
                        "NOT_INDEXED", false, null, null, List.of(), null, null, List.of(), false, null));
        when(connectorService.connect(eq("owner-1"), eq("ws-1"), any()))
                .thenReturn(new GitHubReadmeStatusResponse(
                        "INDEXED",
                        true,
                        1L,
                        "rsrini7/AmshaAgent",
                        List.of("README.md", "docs/**/*.md"),
                        "main",
                        "commit123",
                        List.of(),
                        false,
                        now));

        sync.syncAllWorkspaces();

        verify(connectorService).connect("owner-1", "ws-1", GitHubReadmeSourcePolicy.firstConnection());
        verify(connectorService, never()).refresh(any(), any());
    }

    @Test
    void whenWorkspaceAlreadyIndexedCallsRefresh() {
        GitHubReadmeStartupSync sync =
                new GitHubReadmeStartupSync(connectorService, identityStore, secretVault, true, true);
        when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)).thenReturn(true);
        Instant now = Instant.now();
        when(identityStore.listWorkspaces())
                .thenReturn(List.of(new IdentityWorkspaceDto("ws-1", "Default", "owner-1", now, now)));
        when(connectorService.status("owner-1", "ws-1"))
                .thenReturn(new GitHubReadmeStatusResponse(
                        "INDEXED",
                        true,
                        1L,
                        "rsrini7/AmshaAgent",
                        List.of("README.md", "docs/**/*.md"),
                        "main",
                        "commit123",
                        List.of(),
                        false,
                        now));
        when(connectorService.refresh("owner-1", "ws-1"))
                .thenReturn(new GitHubReadmeStatusResponse(
                        "INDEXED",
                        true,
                        1L,
                        "rsrini7/AmshaAgent",
                        List.of("README.md", "docs/**/*.md"),
                        "main",
                        "commit456",
                        List.of(),
                        false,
                        now));

        sync.syncAllWorkspaces();

        verify(connectorService).refresh("owner-1", "ws-1");
        verify(connectorService, never()).connect(any(), any(), any());
    }

    @Test
    void whenConnectorExceptionThrownFailsGracefully() {
        GitHubReadmeStartupSync sync =
                new GitHubReadmeStartupSync(connectorService, identityStore, secretVault, true, true);
        when(secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)).thenReturn(true);
        when(connectorService.status("owner-1", "ws-1"))
                .thenThrow(new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.SOURCE_UNAVAILABLE));

        // Must not throw
        sync.syncWorkspace("owner-1", "ws-1");

        verify(connectorService).status("owner-1", "ws-1");
    }
}
