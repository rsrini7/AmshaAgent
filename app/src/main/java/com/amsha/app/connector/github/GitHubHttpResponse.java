package com.amsha.app.connector.github;

/** Metadata-only response shape retained by the GitHub client; body is never logged or returned. */
public record GitHubHttpResponse(
        int statusCode, String contentType, String retryAfter, String rateLimitRemaining, String body) {

    @Override
    public String toString() {
        return "GitHubHttpResponse[statusCode=" + statusCode + ", contentType=" + contentType + ", body=[redacted]]";
    }
}
