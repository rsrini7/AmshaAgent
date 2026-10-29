package com.amsha.app.connector.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Opaque archival identifiers scoped to one Amsha owner and workspace. */
final class GitHubReadmeIndexKeys {

    private static final String ROOT_README_PATH = "README.md";
    private static final String NAMESPACE_PREFIX = "github-readme-user-";
    private static final String MANIFEST_PREFIX = "github-readme-manifest-";
    private static final String DOCUMENT_PREFIX = "github-readme-document-";

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

    static String documentId(String userNamespace, long repositoryId, String path) {
        requireValue(userNamespace, "userNamespace");
        if (repositoryId <= 0 || !ROOT_README_PATH.equals(path)) {
            throw new IllegalArgumentException("Only the selected repository root README can be indexed");
        }
        return DOCUMENT_PREFIX + digest(userNamespace + "\u0000" + repositoryId + "\u0000" + path);
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
