package com.amsha.app.connector.github;

/** Fetches only a connection's approved documents at its current default-branch commit. */
public interface GitHubReadmeAppClient {
    /** Failures are classified in payload-free exceptions. */
    GitHubReadmeSnapshot fetchCurrent(String actorUserId, GitHubReadmeConnectionSettings settings);
}
