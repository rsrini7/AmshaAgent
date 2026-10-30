package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Coordinates the owner-authorized GitHub source and its workspace-scoped index. */
@ApplicationScoped
public class GitHubReadmeConnectorService {
    private final GitHubReadmeAppClient sourceClient;
    private final GitHubReadmeIndexStore indexStore;
    private final GitHubReadmeContextMatcher contextMatcher;
    private final boolean enabled;

    @Inject
    public GitHubReadmeConnectorService(
            GitHubReadmeAppClient sourceClient,
            GitHubReadmeIndexStore indexStore,
            GitHubReadmeContextMatcher contextMatcher,
            @ConfigProperty(name = ConfigKeys.GITHUB_DOCS_CONNECTOR_ENABLED_PROPERTY, defaultValue = "false")
                    boolean enabled) {
        this.sourceClient = sourceClient;
        this.indexStore = indexStore;
        this.contextMatcher = contextMatcher;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public GitHubReadmeStatusResponse connect(
            String ownerUserId, String workspaceId, GitHubReadmeConnectionSettings settings) {
        requireEnabled();
        String namespace = GitHubReadmeIndexStore.namespace(ownerUserId, workspaceId);
        GitHubReadmeConnectionSettings approvedSettings = validateSettings(settings);
        try {
            Optional<GitHubReadmeManifestCodec.Manifest> existing = indexStore.readManifest(namespace, ownerUserId);
            if (existing.isPresent() && !existing.get().settings().equals(approvedSettings)) {
                throw invalidSource();
            }
            GitHubReadmeSnapshot snapshot = sourceClient.fetchCurrent(ownerUserId, approvedSettings);
            return indexStore.storeSnapshot(namespace, ownerUserId, approvedSettings, snapshot);
        } catch (GitHubReadmeSourceException e) {
            return handleSourceFailure(namespace, ownerUserId, e.kind());
        } catch (GitHubReadmeConnectorException e) {
            throw e;
        } catch (RuntimeException _) {
            throw indexUnavailable();
        }
    }

    public GitHubReadmeStatusResponse refresh(String ownerUserId, String workspaceId) {
        requireEnabled();
        String namespace = GitHubReadmeIndexStore.namespace(ownerUserId, workspaceId);
        try {
            Optional<GitHubReadmeManifestCodec.Manifest> existing = indexStore.readManifest(namespace, ownerUserId);
            if (existing.isEmpty()) {
                return emptyStatus("NOT_INDEXED");
            }
            GitHubReadmeManifestCodec.Manifest manifest = existing.get();
            GitHubReadmeSnapshot snapshot = sourceClient.fetchCurrent(ownerUserId, manifest.settings());
            return indexStore.storeSnapshot(namespace, ownerUserId, manifest.settings(), snapshot);
        } catch (GitHubReadmeSourceException e) {
            return handleSourceFailure(namespace, ownerUserId, e.kind());
        } catch (GitHubReadmeConnectorException e) {
            throw e;
        } catch (RuntimeException _) {
            throw indexUnavailable();
        }
    }

    public GitHubReadmeStatusResponse status(String ownerUserId, String workspaceId) {
        if (!enabled) {
            return emptyStatus("DISABLED");
        }
        String namespace = GitHubReadmeIndexStore.namespace(ownerUserId, workspaceId);
        try {
            Optional<GitHubReadmeManifestCodec.Manifest> manifest = indexStore.readManifest(namespace, ownerUserId);
            return manifest.map(value -> indexStore.statusFor(namespace, value))
                    .orElseGet(() -> emptyStatus("NOT_INDEXED"));
        } catch (RuntimeException _) {
            return emptyStatus("UNAVAILABLE");
        }
    }

    public GitHubReadmeStatusResponse disconnect(String ownerUserId, String workspaceId) {
        String namespace = GitHubReadmeIndexStore.namespace(ownerUserId, workspaceId);
        try {
            boolean removed = indexStore.removeIndex(namespace, ownerUserId);
            return emptyStatus(removed ? "DISCONNECTED" : "NOT_INDEXED");
        } catch (RuntimeException _) {
            throw indexUnavailable();
        }
    }

    Optional<String> matchingMarkdown(String ownerUserId, String workspaceId, String goal) {
        if (!enabled || ownerUserId == null || ownerUserId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<GitHubReadmeContextMatcher.SourceDocument> documents =
                    indexStore.matchingDocuments(ownerUserId, workspaceId);
            return contextMatcher.match(documents, goal);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.DISABLED);
        }
    }

    private GitHubReadmeStatusResponse handleSourceFailure(
            String namespace, String ownerUserId, GitHubReadmeSourceException.Kind kind) {
        if (kind == GitHubReadmeSourceException.Kind.ACCESS_REVOKED
                || kind == GitHubReadmeSourceException.Kind.SOURCE_NOT_PUBLIC) {
            boolean removed = indexStore.removeIndex(namespace, ownerUserId);
            return emptyStatus(removed ? "REMOVED" : "NOT_INDEXED");
        }
        GitHubReadmeConnectorException.Kind failure =
                switch (kind) {
                    case CREDENTIALS_MISSING -> GitHubReadmeConnectorException.Kind.NOT_CONFIGURED;
                    case TEMPORARY_FAILURE -> GitHubReadmeConnectorException.Kind.SOURCE_UNAVAILABLE;
                    case INVALID_RESPONSE -> GitHubReadmeConnectorException.Kind.INVALID_SOURCE_RESPONSE;
                    case ACCESS_REVOKED, SOURCE_NOT_PUBLIC -> throw new IllegalStateException("Handled above");
                };
        throw new GitHubReadmeConnectorException(failure);
    }

    private static GitHubReadmeConnectionSettings validateSettings(GitHubReadmeConnectionSettings settings) {
        try {
            return GitHubReadmeSourcePolicy.validateFirstConnection(settings);
        } catch (IllegalArgumentException _) {
            throw invalidSource();
        }
    }

    private static GitHubReadmeStatusResponse emptyStatus(String state) {
        return new GitHubReadmeStatusResponse(state, false, null, null, List.of(), null, null, List.of(), false, null);
    }

    private static GitHubReadmeConnectorException invalidSource() {
        return new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INVALID_SOURCE_RESPONSE);
    }

    private static GitHubReadmeConnectorException indexUnavailable() {
        return new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
    }
}
