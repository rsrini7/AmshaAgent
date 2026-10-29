package com.amsha.app.connector.github;

import java.time.Instant;

/** Payload-free owner-facing connection and index status. */
public record GitHubReadmeStatusResponse(
        String state, boolean indexed, Long repositoryId, String defaultBranch, String commitSha, Instant updatedAt) {}
