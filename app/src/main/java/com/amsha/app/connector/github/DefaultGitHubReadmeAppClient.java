package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import com.amsha.core.security.CredentialDetector;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** GitHub App client limited by the persisted repository and path settings for a connection. */
@ApplicationScoped
public class DefaultGitHubReadmeAppClient implements GitHubReadmeAppClient {
    private static final String API_BASE = "https://api.github.com";
    private static final String INSTALLATION_OPERATION = "get_installation";
    private static final String METADATA_TOKEN_OPERATION = "create_installation_metadata_token";
    private static final String INSTALLATION_REPOSITORIES_OPERATION = "list_installation_repositories";
    private static final String TOKEN_OPERATION = "create_repository_token";
    private static final String REPOSITORY_OPERATION = "read_repository_metadata";
    private static final String COMMIT_OPERATION = "read_default_branch_commit";
    private static final String TREE_OPERATION = "read_default_branch_tree";
    private static final String DOCUMENT_OPERATION = "read_allowed_markdown";

    private final GitHubReadmeTransport transport;
    private final SecretVault secretVault;
    private final GitHubReadmePayloadParser parser;
    private final CredentialDetector credentialDetector;
    private final String appId;

    @Inject
    public DefaultGitHubReadmeAppClient(
            GitHubReadmeTransport transport,
            SecretVault secretVault,
            GitHubReadmePayloadParser parser,
            CredentialDetector credentialDetector,
            @ConfigProperty(name = "amsha.github.app-id", defaultValue = "") String appId) {
        this.transport = transport;
        this.secretVault = secretVault;
        this.parser = parser;
        this.credentialDetector = credentialDetector;
        this.appId = appId;
    }

    @Override
    public GitHubReadmeSnapshot fetchCurrent(String actorUserId, GitHubReadmeConnectionSettings settings) {
        GitHubReadmeConnectionSettings validatedSettings;
        try {
            validatedSettings = GitHubReadmeSourcePolicy.validateFirstConnection(settings);
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
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
            return fetchAuthorizedRepository(actorUserId, appJwt, validatedSettings);
        } catch (GitHubReadmeSourceException e) {
            throw e;
        } catch (RuntimeException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.TEMPORARY_FAILURE);
        }
    }

    private GitHubReadmeSnapshot fetchAuthorizedRepository(
            String actorUserId, String appJwt, GitHubReadmeConnectionSettings settings) {
        String[] repositoryParts = settings.repository().split("/", 2);
        String owner = encodePathSegment(repositoryParts[0]);
        String repository = encodePathSegment(repositoryParts[1]);
        String repositoryPath = "/repos/" + owner + "/" + repository;

        GitHubHttpResponse installationResponse =
                send(get(API_BASE + repositoryPath + "/installation", appJwt), actorUserId, INSTALLATION_OPERATION);
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
                parseInstalledRepositories(installedRepositoriesResponse, settings.repository());

        String tokenBody =
                "{\"repositories\":[\"" + repositoryParts[1] + "\"],\"permissions\":{\"contents\":\"read\"}}";
        GitHubHttpResponse tokenResponse = send(
                post(API_BASE + "/app/installations/" + installation.id() + "/access_tokens", appJwt, tokenBody),
                actorUserId,
                TOKEN_OPERATION);
        requireSuccess(tokenResponse);
        GitHubReadmePayloadParser.InstallationToken token = parseToken(tokenResponse, settings.repository());
        if (token.repositoryId() != installedRepository.id()) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }

        GitHubHttpResponse repositoryResponse =
                send(get(API_BASE + repositoryPath, token.value()), actorUserId, REPOSITORY_OPERATION);
        requireSuccess(repositoryResponse);
        GitHubReadmePayloadParser.Repository repositoryMetadata =
                parseRepository(repositoryResponse, settings.repository());
        if (token.repositoryId() != repositoryMetadata.id()) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }

        String encodedBranch = encodePathSegment(repositoryMetadata.defaultBranch());
        GitHubHttpResponse commitResponse = send(
                get(API_BASE + repositoryPath + "/commits/" + encodedBranch, token.value()),
                actorUserId,
                COMMIT_OPERATION);
        requireSuccess(commitResponse);
        String commitSha = parseCommit(commitResponse);

        GitHubHttpResponse treeResponse = send(
                get(API_BASE + repositoryPath + "/git/trees/" + commitSha + "?recursive=1", token.value()),
                actorUserId,
                TREE_OPERATION);
        if (treeResponse.statusCode() == 404) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
        requireSuccess(treeResponse);
        List<GitHubReadmePayloadParser.TreeEntry> allowedEntries = allowedEntries(parseTree(treeResponse), settings);
        List<GitHubReadmeDocumentSnapshot> documents =
                fetchDocuments(actorUserId, token.value(), repositoryPath, commitSha, allowedEntries, settings);
        return new GitHubReadmeSnapshot(
                repositoryMetadata.id(),
                settings.repository(),
                repositoryMetadata.defaultBranch(),
                commitSha,
                documents);
    }

    private List<GitHubReadmePayloadParser.TreeEntry> allowedEntries(
            List<GitHubReadmePayloadParser.TreeEntry> entries, GitHubReadmeConnectionSettings settings) {
        ArrayList<GitHubReadmePayloadParser.TreeEntry> allowed = new ArrayList<>();
        long totalBytes = 0;
        for (GitHubReadmePayloadParser.TreeEntry entry : entries) {
            if (!GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, entry.path())
                    || !"blob".equals(entry.type())
                    || !"100644".equals(entry.mode())) {
                continue;
            }
            if (entry.size() > GitHubReadmeSnapshot.MAX_DOCUMENT_BYTES) {
                continue;
            }
            totalBytes += entry.size();
            if (allowed.size() >= GitHubReadmeSnapshot.MAX_DOCUMENT_COUNT
                    || totalBytes > GitHubReadmeSnapshot.MAX_TOTAL_BYTES) {
                throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
            }
            allowed.add(entry);
        }
        return List.copyOf(allowed);
    }

    private List<GitHubReadmeDocumentSnapshot> fetchDocuments(
            String actorUserId,
            String token,
            String repositoryPath,
            String commitSha,
            List<GitHubReadmePayloadParser.TreeEntry> entries,
            GitHubReadmeConnectionSettings settings) {
        ArrayList<GitHubReadmeDocumentSnapshot> documents = new ArrayList<>(entries.size());
        for (GitHubReadmePayloadParser.TreeEntry entry : entries) {
            if (!GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, entry.path())) {
                throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
            }
            String encodedPath = encodeDocumentPath(entry.path());
            GitHubHttpResponse documentResponse = send(
                    get(API_BASE + repositoryPath + "/contents/" + encodedPath + "?ref=" + commitSha, token),
                    actorUserId,
                    DOCUMENT_OPERATION);
            if (documentResponse.statusCode() == 404) {
                throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
            }
            requireSuccess(documentResponse);
            GitHubReadmePayloadParser.Readme document = parseDocument(documentResponse, entry.path());
            if (!entry.sha().equals(document.blobSha())
                    || document.markdown().getBytes(StandardCharsets.UTF_8).length != entry.size()) {
                throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
            }
            if (credentialDetected(document.markdown())) {
                continue;
            }
            documents.add(new GitHubReadmeDocumentSnapshot(document.path(), document.markdown(), document.blobSha()));
        }
        return List.copyOf(documents);
    }

    private boolean credentialDetected(String markdown) {
        if (credentialDetector == null) {
            return false;
        }
        try {
            return credentialDetector.detect(markdown).detected();
        } catch (RuntimeException _) {
            return true;
        }
    }

    private GitHubReadmePayloadParser.Installation parseInstallation(GitHubHttpResponse response) {
        try {
            return parser.parseInstallation(requireJson(response));
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.InstallationToken parseToken(
            GitHubHttpResponse response, String repositoryFullName) {
        try {
            return parser.parseToken(requireJson(response), repositoryFullName);
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

    private GitHubReadmePayloadParser.InstalledRepository parseInstalledRepositories(
            GitHubHttpResponse response, String repositoryFullName) {
        try {
            return parser.parseInstalledRepositories(requireJson(response), repositoryFullName);
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.Repository parseRepository(
            GitHubHttpResponse response, String repositoryFullName) {
        try {
            return parser.parseRepository(requireJson(response), repositoryFullName);
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

    private List<GitHubReadmePayloadParser.TreeEntry> parseTree(GitHubHttpResponse response) {
        try {
            return parser.parseTree(requireJson(response));
        } catch (IllegalArgumentException _) {
            throw sourceFailure(GitHubReadmeSourceException.Kind.INVALID_RESPONSE);
        }
    }

    private GitHubReadmePayloadParser.Readme parseDocument(GitHubHttpResponse response, String path) {
        try {
            return parser.parseDocument(requireJson(response), path, GitHubReadmeSnapshot.MAX_DOCUMENT_BYTES);
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
                .header("User-Agent", "Amsha-GitHub-Documentation-Connector")
                .header("Authorization", "Bearer " + credential)
                .GET()
                .build();
    }

    private static HttpRequest post(String uri, String credential, String body) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Amsha-GitHub-Documentation-Connector")
                .header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeDocumentPath(String path) {
        return Arrays.stream(path.split("/"))
                .map(DefaultGitHubReadmeAppClient::encodePathSegment)
                .collect(Collectors.joining("/"));
    }

    private static GitHubReadmeSourceException sourceFailure(GitHubReadmeSourceException.Kind kind) {
        return new GitHubReadmeSourceException(kind);
    }
}
