package com.amsha.app.connector.github;

/** Safe, payload-free classification for GitHub source failures. */
public final class GitHubReadmeSourceException extends RuntimeException {

    public enum Kind {
        CREDENTIALS_MISSING,
        ACCESS_REVOKED,
        TEMPORARY_FAILURE,
        INVALID_RESPONSE
    }

    private final Kind kind;

    public GitHubReadmeSourceException(Kind kind) {
        super("GitHub README source request failed");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
