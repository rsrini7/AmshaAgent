package com.amsha.app.connector.github;

import java.util.List;

/** Persisted repository and path scope for one owner/workspace connection. */
public record GitHubReadmeConnectionSettings(String repository, List<String> allowedPaths) {
    public GitHubReadmeConnectionSettings {
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
    }
}
