package com.amsha.app.connector.github;

/** Metadata only; document contents are never returned by connector status APIs. */
public record GitHubReadmeDocumentStatusResponse(String path, String commitSha, String blobSha) {}
