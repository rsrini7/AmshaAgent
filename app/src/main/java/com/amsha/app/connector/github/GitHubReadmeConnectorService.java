package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.memory.tiered.TieredBackendLiteral;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Owner/workspace-scoped indexing and retrieval for one public GitHub README. */
@ApplicationScoped
public class GitHubReadmeConnectorService {

    private static final String ROOT_README_PATH = "README.md";
    private static final String MANIFEST_SOURCE = "github-readme-index-manifest";
    private static final String CONTENT_SOURCE_PREFIX = "github-readme:";
    private static final int MAX_EXCERPTS = 4;
    private static final int MAX_EXCERPT_CHARS = 700;
    private static final int MAX_CONTEXT_CHARS = 2400;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    private final Instance<ArchivalMemoryStorePort> archivalStores;
    private final GitHubReadmeAppClient sourceClient;
    private final AuditService auditService;
    private final ObjectMapper mapper;
    private final String persistenceBackend;

    @Inject
    public GitHubReadmeConnectorService(
            @Any Instance<ArchivalMemoryStorePort> archivalStores,
            GitHubReadmeAppClient sourceClient,
            AuditService auditService,
            ObjectMapper mapper,
            @ConfigProperty(name = PersistenceBackend.PROPERTY, defaultValue = PersistenceBackend.SQLITE)
                    String persistenceBackend) {
        this.archivalStores = archivalStores;
        this.sourceClient = sourceClient;
        this.auditService = auditService;
        this.mapper = mapper;
        this.persistenceBackend = persistenceBackend;
    }

    public GitHubReadmeStatusResponse connect(String ownerUserId, String workspaceId) {
        return refresh(ownerUserId, workspaceId);
    }

    public GitHubReadmeStatusResponse refresh(String ownerUserId, String workspaceId) {
        String namespace = namespace(ownerUserId, workspaceId);
        try {
            Optional<GitHubReadmeSnapshot> snapshot = sourceClient.fetchCurrent(ownerUserId);
            if (snapshot.isEmpty()) {
                boolean removed = removeIndex(namespace, ownerUserId);
                return emptyStatus(removed ? "REMOVED" : "NOT_INDEXED");
            }
            return storeSnapshot(namespace, ownerUserId, snapshot.get());
        } catch (GitHubReadmeSourceException e) {
            return handleSourceFailure(namespace, ownerUserId, e.kind());
        } catch (GitHubReadmeConnectorException e) {
            throw e;
        } catch (RuntimeException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
    }

    public GitHubReadmeStatusResponse status(String ownerUserId, String workspaceId) {
        String namespace = namespace(ownerUserId, workspaceId);
        try {
            Manifest manifest = readManifest(namespace);
            if (manifest == null) {
                return emptyStatus("NOT_INDEXED");
            }
            return statusFor(namespace, manifest);
        } catch (RuntimeException _) {
            return emptyStatus("UNAVAILABLE");
        }
    }

    public GitHubReadmeStatusResponse disconnect(String ownerUserId, String workspaceId) {
        String namespace = namespace(ownerUserId, workspaceId);
        boolean removed;
        try {
            removed = removeIndex(namespace, ownerUserId);
        } catch (RuntimeException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        return emptyStatus(removed ? "DISCONNECTED" : "NOT_INDEXED");
    }

    Optional<String> matchingMarkdown(String ownerUserId, String workspaceId, String goal) {
        if (ownerUserId == null || ownerUserId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            return Optional.empty();
        }
        Set<String> queryWords = words(goal);
        if (queryWords.isEmpty()) {
            return Optional.empty();
        }
        try {
            String namespace = namespace(ownerUserId, workspaceId);
            Manifest manifest = readManifest(namespace);
            if (manifest == null) {
                return Optional.empty();
            }
            return getContent(namespace, manifest).flatMap(content -> selectExcerpts(content.content(), queryWords));
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }

    private GitHubReadmeStatusResponse storeSnapshot(
            String namespace, String ownerUserId, GitHubReadmeSnapshot snapshot) {
        Manifest previous = readManifest(namespace);
        String documentId = GitHubReadmeIndexKeys.documentId(namespace, snapshot.repositoryId(), ROOT_README_PATH);
        Instant updatedAt = Instant.now();
        ArchivalChunkDto existing = archivalStore().getById(documentId).orElse(null);
        validateExistingContent(namespace, existing, snapshot.repositoryId());
        if (isUnchanged(existing, previous, snapshot)) {
            return statusFor(namespace, previous);
        }

        auditMutation(ownerUserId, "refresh");
        ArchivalChunkDto content = contentChunk(namespace, documentId, snapshot, existing, updatedAt);
        try {
            archivalStore().upsert(content);
            if (previous != null && !previous.documentId().equals(documentId)) {
                archivalStore().delete(previous.documentId());
            }
            archivalStore().upsert(manifestChunk(namespace, snapshot, documentId, updatedAt));
        } catch (RuntimeException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        return statusFor(namespace, readManifest(namespace));
    }

    private GitHubReadmeStatusResponse handleSourceFailure(
            String namespace, String ownerUserId, GitHubReadmeSourceException.Kind kind) {
        if (kind == GitHubReadmeSourceException.Kind.ACCESS_REVOKED) {
            boolean removed = removeIndex(namespace, ownerUserId);
            return emptyStatus(removed ? "REMOVED" : "NOT_INDEXED");
        }
        GitHubReadmeConnectorException.Kind failure =
                switch (kind) {
                    case CREDENTIALS_MISSING -> GitHubReadmeConnectorException.Kind.NOT_CONFIGURED;
                    case TEMPORARY_FAILURE -> GitHubReadmeConnectorException.Kind.SOURCE_UNAVAILABLE;
                    case INVALID_RESPONSE -> GitHubReadmeConnectorException.Kind.INVALID_SOURCE_RESPONSE;
                    case ACCESS_REVOKED -> throw new IllegalStateException("Access revocation was already handled");
                };
        throw new GitHubReadmeConnectorException(failure);
    }

    private boolean removeIndex(String namespace, String ownerUserId) {
        Manifest manifest = readManifest(namespace);
        if (manifest == null) {
            return false;
        }
        auditMutation(ownerUserId, "remove");
        try {
            archivalStore().delete(manifest.documentId());
            archivalStore().delete(GitHubReadmeIndexKeys.manifestId(namespace));
            return true;
        } catch (RuntimeException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
    }

    private void auditMutation(String ownerUserId, String operation) {
        auditService.recordMandatory(
                null,
                ownerUserId,
                "github-readme-connector",
                SecurityEventType.EXTERNAL_SOURCE_INDEX_MUTATED,
                "operation=" + operation + " source=github-readme");
    }

    private GitHubReadmeStatusResponse statusFor(String namespace, Manifest manifest) {
        if (manifest == null) {
            return emptyStatus("NOT_INDEXED");
        }
        return getContent(namespace, manifest)
                .map(content -> new GitHubReadmeStatusResponse(
                        "INDEXED",
                        true,
                        manifest.repositoryId(),
                        manifest.defaultBranch(),
                        manifest.commitSha(),
                        content.updatedAt()))
                .orElseGet(() -> emptyStatus("UNAVAILABLE"));
    }

    private Optional<ArchivalChunkDto> getContent(String namespace, Manifest manifest) {
        return archivalStore()
                .getById(manifest.documentId())
                .filter(content -> isValidContent(namespace, content, manifest));
    }

    private boolean isValidContent(String namespace, ArchivalChunkDto content, Manifest manifest) {
        return namespace.equals(content.userId())
                && "DOC".equals(content.chunkType())
                && (CONTENT_SOURCE_PREFIX + manifest.repositoryId()).equals(content.sourceSession())
                && ROOT_README_PATH.equals(readTagsPath(content.tags()))
                && manifest.commitSha().equals(content.sourceMessage());
    }

    private String readTagsPath(String tagsJson) {
        try {
            JsonNode tags = parseJson(tagsJson);
            if (!tags.isArray()) {
                return "";
            }
            for (JsonNode tag : tags) {
                if (tag.isTextual() && tag.textValue().startsWith("path=")) {
                    return tag.textValue().substring("path=".length());
                }
            }
            return "";
        } catch (RuntimeException _) {
            return "";
        }
    }

    private void validateExistingContent(String namespace, ArchivalChunkDto existing, long repositoryId) {
        if (existing != null
                && (!namespace.equals(existing.userId())
                        || !"DOC".equals(existing.chunkType())
                        || !(CONTENT_SOURCE_PREFIX + repositoryId).equals(existing.sourceSession()))) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
    }

    private boolean isUnchanged(ArchivalChunkDto existing, Manifest previous, GitHubReadmeSnapshot snapshot) {
        return existing != null
                && previous != null
                && previous.documentId().equals(existing.chunkId())
                && previous.commitSha().equals(snapshot.commitSha())
                && existing.sourceMessage().equals(snapshot.commitSha())
                && existing.content().equals(snapshot.markdown())
                && previous.defaultBranch().equals(snapshot.defaultBranch());
    }

    private ArchivalChunkDto contentChunk(
            String namespace,
            String documentId,
            GitHubReadmeSnapshot snapshot,
            ArchivalChunkDto existing,
            Instant updatedAt) {
        String tags = writeTags(snapshot.defaultBranch());
        return new ArchivalChunkDto(
                documentId,
                namespace,
                snapshot.markdown(),
                "DOC",
                CONTENT_SOURCE_PREFIX + snapshot.repositoryId(),
                snapshot.commitSha(),
                tags,
                1.0,
                existing == null ? 0 : existing.accessCount(),
                existing == null || existing.createdAt() == null ? updatedAt : existing.createdAt(),
                updatedAt);
    }

    private ArchivalChunkDto manifestChunk(
            String namespace, GitHubReadmeSnapshot snapshot, String documentId, Instant updatedAt) {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("repositoryId", snapshot.repositoryId());
        manifest.put("repositoryFullName", snapshot.repositoryFullName());
        manifest.put("path", ROOT_README_PATH);
        manifest.put("defaultBranch", snapshot.defaultBranch());
        manifest.put("commitSha", snapshot.commitSha());
        manifest.put("documentId", documentId);
        manifest.put("updatedAt", updatedAt.toString());
        return new ArchivalChunkDto(
                GitHubReadmeIndexKeys.manifestId(namespace),
                namespace,
                writeJson(manifest),
                "FACT",
                MANIFEST_SOURCE,
                snapshot.commitSha(),
                "[\"source=github-app-readme\"]",
                1.0,
                0,
                updatedAt,
                updatedAt);
    }

    private String writeTags(String defaultBranch) {
        var tags = mapper.createArrayNode();
        tags.add("source=github-app-readme");
        tags.add("path=" + ROOT_README_PATH);
        tags.add("branch=" + defaultBranch);
        return writeJson(tags);
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
    }

    private Manifest readManifest(String namespace) {
        Optional<ArchivalChunkDto> stored = archivalStore().getById(GitHubReadmeIndexKeys.manifestId(namespace));
        if (stored.isEmpty()) {
            return null;
        }
        ArchivalChunkDto chunk = stored.get();
        if (!namespace.equals(chunk.userId())
                || !"FACT".equals(chunk.chunkType())
                || !MANIFEST_SOURCE.equals(chunk.sourceSession())) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        JsonNode root = parseJson(chunk.content());
        if (!root.isObject()) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        long repositoryId = positiveLong(root, "repositoryId");
        String repository = text(root, "repositoryFullName", 200);
        String path = text(root, "path", 256);
        String branch = text(root, "defaultBranch", 255);
        String commitSha = text(root, "commitSha", 64);
        String documentId = text(root, "documentId", 128);
        Instant updatedAt;
        try {
            updatedAt = Instant.parse(text(root, "updatedAt", 64));
        } catch (RuntimeException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        if (!GitHubReadmeSnapshot.EXPECTED_REPOSITORY.equals(repository)
                || !ROOT_README_PATH.equals(path)
                || !commitSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")
                || !GitHubReadmeIndexKeys.documentId(namespace, repositoryId, path)
                        .equals(documentId)) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        return new Manifest(repositoryId, branch, commitSha.toLowerCase(Locale.ROOT), documentId, updatedAt);
    }

    private JsonNode parseJson(String json) {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw new IllegalArgumentException("Invalid connector metadata");
            }
            return root;
        } catch (IOException | RuntimeException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
    }

    private static long positiveLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        return value.longValue();
    }

    private static String text(JsonNode object, String field, int maxLength) {
        JsonNode value = object.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maxLength) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        return value.textValue();
    }

    private Optional<String> selectExcerpts(String markdown, Set<String> queryWords) {
        List<ScoredExcerpt> candidates = new ArrayList<>();
        for (String paragraph : markdown.split("\\R\\s*\\R")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int score = score(trimmed, queryWords);
            if (score > 0) {
                candidates.add(new ScoredExcerpt(score, clipped(trimmed, MAX_EXCERPT_CHARS)));
            }
        }
        candidates.sort(Comparator.comparingInt(ScoredExcerpt::score).reversed());
        StringBuilder context = new StringBuilder();
        int count = Math.min(candidates.size(), MAX_EXCERPTS);
        for (int index = 0; index < count; index++) {
            String excerpt = candidates.get(index).text();
            int remaining = MAX_CONTEXT_CHARS - context.length();
            if (remaining <= 0) {
                break;
            }
            if (excerpt.length() > remaining) {
                excerpt = excerpt.substring(0, remaining);
            }
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append(excerpt);
        }
        return context.isEmpty() ? Optional.empty() : Optional.of(context.toString());
    }

    private static int score(String paragraph, Set<String> queryWords) {
        Set<String> paragraphWords = words(paragraph);
        int score = 0;
        for (String word : queryWords) {
            if (paragraphWords.contains(word)) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> words(String value) {
        Set<String> words = new HashSet<>();
        if (value == null || value.isBlank()) {
            return words;
        }
        Matcher matcher = WORD_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static String clipped(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    private String namespace(String ownerUserId, String workspaceId) {
        try {
            return GitHubReadmeIndexKeys.userNamespace(ownerUserId, workspaceId);
        } catch (IllegalArgumentException _) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
    }

    private ArchivalMemoryStorePort archivalStore() {
        String backend = persistenceBackend == null || persistenceBackend.isBlank()
                ? PersistenceBackend.SQLITE
                : persistenceBackend.trim().toLowerCase(Locale.ROOT);
        Instance<ArchivalMemoryStorePort> selected = archivalStores.select(new TieredBackendLiteral(backend));
        if (selected.isUnsatisfied() || selected.isAmbiguous()) {
            throw new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
        }
        return selected.get();
    }

    private static GitHubReadmeStatusResponse emptyStatus(String state) {
        return new GitHubReadmeStatusResponse(state, false, null, null, null, null);
    }

    private record Manifest(
            long repositoryId, String defaultBranch, String commitSha, String documentId, Instant updatedAt) {}

    private record ScoredExcerpt(int score, String text) {}
}
