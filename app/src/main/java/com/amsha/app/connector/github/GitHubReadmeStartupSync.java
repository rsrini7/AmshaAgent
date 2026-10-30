package com.amsha.app.connector.github;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.List;

import com.amsha.common.config.ConfigKeys;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;

/**
 * Automatically connects and syncs GitHub documentation on server boot when enabled.
 */
@ApplicationScoped
@Startup
@Unremovable
public class GitHubReadmeStartupSync {

    private static final Logger log = Logger.getLogger(GitHubReadmeStartupSync.class);

    private final GitHubReadmeConnectorService connectorService;
    private final IdentityStorePort identityStore;
    private final SecretVault secretVault;
    private final boolean connectorEnabled;
    private final boolean autoSyncOnStartup;

    @Inject
    public GitHubReadmeStartupSync(
            GitHubReadmeConnectorService connectorService,
            IdentityStorePort identityStore,
            SecretVault secretVault,
            @ConfigProperty(name = ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED_PROPERTY, defaultValue = "false")
                    boolean connectorEnabled,
            @ConfigProperty(name = ConfigKeys.GITHUB_DOCS_AUTO_SYNC_ON_STARTUP_PROPERTY, defaultValue = "true")
                    boolean autoSyncOnStartup) {
        this.connectorService = connectorService;
        this.identityStore = identityStore;
        this.secretVault = secretVault;
        this.connectorEnabled = connectorEnabled;
        this.autoSyncOnStartup = autoSyncOnStartup;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 500) StartupEvent event) {
        if (!connectorEnabled || !autoSyncOnStartup) {
            return;
        }
        syncAllWorkspacesAsync();
    }

    public void syncAllWorkspacesAsync() {
        if (!connectorEnabled || !autoSyncOnStartup) {
            return;
        }
        Thread.ofVirtual().name("github-docs-startup-sync").start(this::syncAllWorkspaces);
    }

    public void syncAllWorkspaces() {
        if (!connectorEnabled) {
            return;
        }
        if (!secretVault.systemKeyExists(VaultConstants.GITHUB_APP_PRIVATE_KEY)) {
            log.info(
                    "GitHub documentation connector is enabled, but GitHub App credential is not yet configured in SecretVault. Auto-sync will run once credential is uploaded.");
            return;
        }
        List<IdentityWorkspaceDto> workspaces = identityStore.listWorkspaces();
        if (workspaces.isEmpty()) {
            return;
        }
        for (IdentityWorkspaceDto workspace : workspaces) {
            syncWorkspace(workspace.createdBy(), workspace.workspaceId());
        }
    }

    public void syncWorkspace(String ownerUserId, String workspaceId) {
        if (ownerUserId == null || ownerUserId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        try {
            GitHubReadmeStatusResponse status = connectorService.status(ownerUserId, workspaceId);
            if (status.indexed() || "INDEXED".equalsIgnoreCase(status.state())) {
                GitHubReadmeStatusResponse refreshed = connectorService.refresh(ownerUserId, workspaceId);
                log.infof(
                        "GitHub documentation auto-refreshed for workspace %s (commit: %s, documents: %d)",
                        workspaceId,
                        refreshed.commitSha(),
                        refreshed.documents().size());
            } else {
                GitHubReadmeStatusResponse connected =
                        connectorService.connect(ownerUserId, workspaceId, GitHubReadmeSourcePolicy.firstConnection());
                if (connected.indexed() || "INDEXED".equalsIgnoreCase(connected.state())) {
                    log.infof(
                            "GitHub documentation auto-connected for workspace %s (repository: %s, commit: %s, documents: %d)",
                            workspaceId,
                            connected.repository(),
                            connected.commitSha(),
                            connected.documents().size());
                } else {
                    log.infof(
                            "GitHub documentation auto-connect for workspace %s completed with state: %s",
                            workspaceId, connected.state());
                }
            }
        } catch (GitHubReadmeConnectorException e) {
            log.warnf(
                    "GitHub documentation auto-sync skipped for workspace %s: %s (%s)",
                    workspaceId, e.kind(), e.getMessage());
        } catch (RuntimeException e) {
            log.warnf("GitHub documentation auto-sync error for workspace %s: %s", workspaceId, e.getMessage());
        }
    }
}
