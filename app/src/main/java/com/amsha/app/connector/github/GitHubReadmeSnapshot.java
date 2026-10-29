package com.amsha.app.connector.github;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Validated public README snapshot fetched from the repository's default branch. */
public record GitHubReadmeSnapshot(
        long repositoryId, String repositoryFullName, String defaultBranch, String commitSha, String markdown) {

    public static final int MAX_BYTES = 65_536;
    public static final String EXPECTED_REPOSITORY = "rsrini7/rsrini7";

    public GitHubReadmeSnapshot {
        if (repositoryId <= 0
                || !EXPECTED_REPOSITORY.equals(repositoryFullName)
                || defaultBranch == null
                || defaultBranch.isBlank()
                || defaultBranch.length() > 255
                || defaultBranch.contains("\n")
                || defaultBranch.contains("\r")
                || commitSha == null
                || !commitSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")
                || markdown == null
                || markdown.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("GitHub source snapshot did not match the connector contract");
        }
        commitSha = commitSha.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "GitHubReadmeSnapshot[repositoryId=" + repositoryId + ", repositoryFullName=" + repositoryFullName
                + ", defaultBranch=" + defaultBranch + ", commitSha=" + commitSha + ", markdown=[redacted]]";
    }
}
