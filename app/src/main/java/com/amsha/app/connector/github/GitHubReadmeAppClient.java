package com.amsha.app.connector.github;

import java.util.Optional;

/** Fetches the selected repository's current default-branch README through a GitHub App. */
public interface GitHubReadmeAppClient {

    /**
     * Returns an empty result only when the repository is accessible and its root README is absent.
     * Other failures are classified with a payload-free exception.
     */
    Optional<GitHubReadmeSnapshot> fetchCurrent(String actorUserId);
}
