package com.amsha.app.connector.github;

/** Safe failures returned by the owner-facing connector API. */
public final class GitHubReadmeConnectorException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED,
        SOURCE_UNAVAILABLE,
        INVALID_SOURCE_RESPONSE,
        INDEX_UNAVAILABLE
    }

    private final Kind kind;

    public GitHubReadmeConnectorException(Kind kind) {
        super("GitHub README connector request failed");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
