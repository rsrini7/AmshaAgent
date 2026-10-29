package com.amsha.app.connector.github;

import java.util.Locale;

/** One validated Markdown document at a repository commit. */
public record GitHubReadmeDocumentSnapshot(String path, String markdown, String blobSha) {
    public GitHubReadmeDocumentSnapshot {
        if (!GitHubReadmeSourcePolicy.isSafeRelativePath(path)
                || markdown == null
                || blobSha == null
                || !blobSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("GitHub document snapshot did not match connector contract");
        }
        blobSha = blobSha.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "GitHubReadmeDocumentSnapshot[path=" + path + ", markdown=[redacted], blobSha=" + blobSha + "]";
    }
}
