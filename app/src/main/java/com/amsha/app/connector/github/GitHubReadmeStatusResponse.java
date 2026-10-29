package com.amsha.app.connector.github;

import java.time.Instant;
import java.util.List;

/** Payload-free owner-facing connection and index status. */
public record GitHubReadmeStatusResponse(
        String state,
        boolean indexed,
        Long repositoryId,
        String repository,
        List<String> allowedPaths,
        String defaultBranch,
        String commitSha,
        List<GitHubReadmeDocumentStatusResponse> documents,
        boolean cleanupPending,
        Instant updatedAt) {
    public GitHubReadmeStatusResponse {
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
