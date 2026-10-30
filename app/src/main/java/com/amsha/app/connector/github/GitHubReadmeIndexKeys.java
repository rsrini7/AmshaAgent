package com.amsha.app.connector.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Opaque archival identifiers scoped to one Amsha owner, workspace, repository, and path. */
final class GitHubReadmeIndexKeys {
    static final String NAMESPACE_PREFIX = "github-readme-user-";
    private static final String MANIFEST_PREFIX = "github-readme-manifest-";
    static final String DOCUMENT_PREFIX = "github-readme-document-";

    private GitHubReadmeIndexKeys() {}

    static String userNamespace(String ownerUserId, String workspaceId) {
        requireValue(ownerUserId, "ownerUserId");
        requireValue(workspaceId, "workspaceId");
        return NAMESPACE_PREFIX + digest(ownerUserId + "\u0000" + workspaceId);
    }

    static String manifestId(String userNamespace) {
        requireValue(userNamespace, "userNamespace");
        return MANIFEST_PREFIX + digest(userNamespace);
    }

    static String documentId(String userNamespace, long repositoryId, String path, String commitSha) {
        requireValue(userNamespace, "userNamespace");
        if (repositoryId <= 0
                || !GitHubReadmeSourcePolicy.isSafeRelativePath(path)
                || commitSha == null
                || !commitSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("Invalid GitHub source document identity");
        }
        return DOCUMENT_PREFIX
                + digest(userNamespace
                        + "\u0000"
                        + repositoryId
                        + "\u0000"
                        + path
                        + "\u0000"
                        + commitSha.toLowerCase(Locale.ROOT));
    }

    static String legacyDocumentId(String userNamespace, long repositoryId, String path) {
        requireValue(userNamespace, "userNamespace");
        if (repositoryId <= 0 || !GitHubReadmeSourcePolicy.isSafeRelativePath(path)) {
            throw new IllegalArgumentException("Invalid legacy GitHub source document identity");
        }
        return "github-readme-document-" + digest(userNamespace + "\u0000" + repositoryId + "\u0000" + path);
    }

    static boolean isDocumentId(String value) {
        return value != null && value.matches("^" + DOCUMENT_PREFIX + "[0-9a-f]{64}$");
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
