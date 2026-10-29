package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** GitHub App client restricted to the selected repository's public root README. */
@ApplicationScoped
public class DefaultGitHubReadmeAppClient implements GitHubReadmeAppClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String OWNER = "rsrini7";
    private static final String REPOSITORY = "rsrini7";
    private static final String FULL_NAME = "rsrini7/rsrini7";
    private static final String INSTALLATION_OPERATION = "get_installation";
    private static final String METADATA_TOKEN_OPERATION = "create_installation_metadata_token";
    private static final String INSTALLATION_REPOSITORIES_OPERATION = "list_installation_repositories";
    private static final String TOKEN_OPERATION = "create_repository_token";
    private static final String REPOSITORY_OPERATION = "read_repository_metadata";
    private static final String COMMIT_OPERATION = "read_default_branch_commit";
    private static final String README_OPERATION = "read_root_readme";

    private final GitHubReadmeTransport transport;
    private final SecretVault secretVault;
    private final GitHubReadmePayloadParser parser;
    private final String appId;

    @Inject
    public DefaultGitHubReadmeAppClient(
            GitHubReadmeTransport transport,
            SecretVault secretVault,
            GitHubReadmePayloadParser parser,
            @ConfigProperty(name = "amsha.github.app-id", defaultValue = "") String appId) {
        this.transport = transport;
        this.secretVault = secretVault;
        this.parser = parser;
        this.appId = appId;
    }

    @Override
    public Optional<GitHubReadmeSnapshot> fetchCurrent(String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank() || appId == null || !appId.matches("[0-9]{1,20}")) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.CREDENTIALS_MISSING);
        }
        Optional<VaultKey> storedKey;
        try {
            storedKey = secretVault.retrieveSystemKey(VaultConstants.GITHUB_APP_PRIVATE_KEY);
        } catch (RuntimeException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE);
        }
        if (storedKey.isEmpty()) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.CREDENTIALS_MISSING);
        }
        try (VaultKey appPrivateKey = storedKey.get()) {
            String appJwt;
            try {
                appJwt = GitHubAppJwtSigner.createJwt(appId, appPrivateKey.get(), Instant.now());
            } catch (IllegalArgumentException _) {
                throw sourceFailure(GitHubReadmeSourceException.Kind.CREDENTIALS_MISSING);
            }
            return fetchAuthorizedRepository(actorUserId, appJwt);
        } catch (GitHubReadmeSourceException e) {
            throw e;
        } catch (RuntimeException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE);
        }
    }

    private Optional<GitHubReadmeSnapshot> fetchAuthorizedRepository(String actorUserId, String appJwt) {
        GitHubHttpResponse installationResponse = send(
                get(API_BASE + "/repos/" + OWNER + "/" + REPOSITORY + "/installation", appJwt),
                actorUserId,
                INSTALLATION_OPERATION);
        requireSuccess(installationResponse);
        GitHubReadmePayloadParser.Installation installation = parseInstallation(installationResponse);

        String metadataTokenBody = "{\"permissions\":{\"metadata\":\"read\"}}";
        GitHubHttpResponse metadataTokenResponse = send(
                post(
                        API_BASE + "/app/installations/" + installation.id() + "/access_tokens",
                        appJwt,
                        metadataTokenBody),
                actorUserId,
                METADATA_TOKEN_OPERATION);
        requireSuccess(metadataTokenResponse);
        GitHubReadmePayloadParser.MetadataToken metadataToken = parseMetadataToken(metadataTokenResponse);

        GitHubHttpResponse installedRepositoriesResponse = send(
                get(API_BASE + "/installation/repositories?per_page=100&page=1", metadataToken.value()),
                actorUserId,
                INSTALLATION_REPOSITORIES_OPERATION);
        requireSuccess(installedRepositoriesResponse);
        GitHubReadmePayloadParser.InstalledRepository installedRepository =
                parseInstalledRepositories(installedRepositoriesResponse);

        String tokenBody = "{\"repositories\":[\"" + REPOSITORY + "\"],\"permissions\":{\"contents\":\"read\"}}";
        GitHubHttpResponse tokenResponse = send(
                post(API_BASE + "/app/installations/" + installation.id() + "/access_tokens", appJwt, tokenBody),
                actorUserId,
                TOKEN_OPERATION);
        requireSuccess(tokenResponse);
        GitHubReadmePayloadParser.InstallationToken token = parseToken(tokenResponse);
        if (token.repositoryId() != installedRepository.id()) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }

        GitHubHttpResponse repositoryResponse = send(
                get(API_BASE + "/repos/" + OWNER + "/" + REPOSITORY, token.value()), actorUserId, REPOSITORY_OPERATION);
        requireSuccess(repositoryResponse);
        if (parser.repositoryIsPrivate(requireJson(repositoryResponse))) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.ACCESS_REVOKED);
        }
        GitHubReadmePayloadParser.Repository repository = parseRepository(repositoryResponse);
        if (token.repositoryId() != repository.id()) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }

        String branchPath = encodePathSegment(repository.defaultBranch());
        GitHubHttpResponse commitResponse = send(
                get(API_BASE + "/repos/" + OWNER + "/" + REPOSITORY + "/commits/" + branchPath, token.value()),
                actorUserId,
                COMMIT_OPERATION);
        if (commitResponse.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(commitResponse);
        String commitSha = parseCommit(commitResponse);

        String ref = commitSha;
        GitHubHttpResponse readmeResponse = send(
                get(API_BASE + "/repos/" + OWNER + "/" + REPOSITORY + "/contents/README.md?ref=" + ref, token.value()),
                actorUserId,
                README_OPERATION);
        if (readmeResponse.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(readmeResponse);
        GitHubReadmePayloadParser.Readme readme = parseReadme(readmeResponse);
        return Optional.of(new GitHubReadmeSnapshot(
                repository.id(), FULL_NAME, repository.defaultBranch(), commitSha, readme.markdown()));
    }

    private GitHubReadmePayloadParser.Installation parseInstallation(GitHubHttpResponse response) {
        try {
            return parser.parseInstallation(requireJson(response));
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.InstallationToken parseToken(GitHubHttpResponse response) {
        try {
            return parser.parseToken(requireJson(response), FULL_NAME);
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.MetadataToken parseMetadataToken(GitHubHttpResponse response) {
        try {
            return parser.parseMetadataToken(requireJson(response));
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.InstalledRepository parseInstalledRepositories(GitHubHttpResponse response) {
        try {
            return parser.parseInstalledRepositories(requireJson(response), FULL_NAME);
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.Repository parseRepository(GitHubHttpResponse response) {
        try {
            return parser.parseRepository(requireJson(response), FULL_NAME);
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private String parseCommit(GitHubHttpResponse response) {
        try {
            return parser.parseCommitSha(requireJson(response));
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.Readme parseReadme(GitHubHttpResponse response) {
        try {
            return parser.parseReadme(requireJson(response), GitHubReadmeSnapshot.MAX_BYTES);
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubHttpResponse send(HttpRequest request, String actorUserId, String operation) {
        try {
            return transport.send(request, actorUserId, operation);
        } catch (RuntimeException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE);
        }
    }

    private static void requireSuccess(GitHubHttpResponse response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 429 || (status == 403 && isRateLimited(response))) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE);
        }
        if (status == 401 || status == 403 || status == 404) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.ACCESS_REVOKED);
        }
        throw sourceFailure(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE);
    }

    private static boolean isRateLimited(GitHubHttpResponse response) {
        return "0".equals(response.rateLimitRemaining())
                || !response.retryAfter().isBlank();
    }

    private static String requireJson(GitHubHttpResponse response) {
        if (response.contentType() == null
                || !response.contentType().toLowerCase(Locale.ROOT).contains("json")) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
        return response.body();
    }

    private static HttpRequest get(String uri, String credential) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Amsha-GitHub-Readme-Connector")
                .header("Authorization", "Bearer " + credential)
                .GET()
                .build();
    }

    private static HttpRequest post(String uri, String credential, String body) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Amsha-GitHub-Readme-Connector")
                .header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static GitHubReadmeSourceException sourceFailure(GitHubReadmeSourceException.Kind kind) {
        return new GitHubReadmeSourceException(kind);
    }
}
