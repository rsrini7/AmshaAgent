package com.amsha.app.connector.github;

/** Credential upload response; never returns key data. */
public record GitHubAppCredentialStatusResponse(boolean configured) {}
