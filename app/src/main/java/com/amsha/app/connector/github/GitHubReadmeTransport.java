package com.amsha.app.connector.github;

import java.net.http.HttpRequest;

/** Governed transport seam for GitHub App REST calls. */
public interface GitHubReadmeTransport {

    GitHubHttpResponse send(HttpRequest request, String actorUserId, String operation);
}
