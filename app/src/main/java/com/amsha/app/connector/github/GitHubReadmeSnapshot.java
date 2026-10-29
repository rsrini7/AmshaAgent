package com.amsha.app.connector.github;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Validated repository snapshot containing only documents permitted by its connection scope. */
public record GitHubReadmeSnapshot(
        long repositoryId,
        String repositoryFullName,
        String defaultBranch,
        String commitSha,
        List<GitHubReadmeDocumentSnapshot> documents) {
    public static final int MAX_DOCUMENT_BYTES = 65_536;
    public static final int MAX_DOCUMENT_COUNT = 256;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

    public GitHubReadmeSnapshot {
        if (repositoryId <= 0
                || repositoryFullName == null
                || !repositoryFullName.equals(repositoryFullName.trim())
                || repositoryFullName.isBlank()
                || defaultBranch == null
                || defaultBranch.isBlank()
                || defaultBranch.length() > 255
                || defaultBranch.contains("\n")
                || defaultBranch.contains("\r")
                || commitSha == null
                || !commitSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")
                || documents == null
                || documents.size() > MAX_DOCUMENT_COUNT) {
            throw new IllegalArgumentException("GitHub source snapshot did not match connector contract");
        }
        commitSha = commitSha.toLowerCase(Locale.ROOT);
        documents = List.copyOf(documents);
        long totalBytes = 0;
        HashSet<String> paths = new HashSet<>();
        for (GitHubReadmeDocumentSnapshot document : documents) {
            if (document == null
                    || !paths.add(document.path())
                    || document.markdown().getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("GitHub source snapshot did not match connector contract");
            }
            totalBytes += document.markdown().getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("GitHub source snapshot exceeded connector bounds");
            }
        }
    }

    @Override
    public String toString() {
        return "GitHubReadmeSnapshot[repositoryId="
                + repositoryId
                + ", repositoryFullName="
                + repositoryFullName
                + ", defaultBranch="
                + defaultBranch
                + ", commitSha="
                + commitSha
                + ", documents="
                + documents.stream().map(GitHubReadmeDocumentSnapshot::path).toList()
                + "]";
    }
}
