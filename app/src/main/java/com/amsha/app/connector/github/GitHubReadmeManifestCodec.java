package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Strict serializer for an owner/workspace-scoped documentation manifest. */
@ApplicationScoped
public class GitHubReadmeManifestCodec {
    private static final int MAX_PENDING_DELETIONS = GitHubReadmeSnapshot.MAX_DOCUMENT_COUNT * 2;

    private final ObjectMapper mapper;

    @Inject
    public GitHubReadmeManifestCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Manifest parse(String json, String namespace) {
        JsonNode root = parseObject(json);
        requireFields(
                root,
                Set.of(
                        "version",
                        "repositoryId",
                        "repository",
                        "allowedPaths",
                        "defaultBranch",
                        "commitSha",
                        "documents",
                        "pendingDeletionIds",
                        "updatedAt"));
        if (positiveLong(root, "version") != 2) {
            throw invalidManifest();
        }

        long repositoryId = positiveLong(root, "repositoryId");
        String repository = text(root, "repository", 200);
        List<String> allowedPaths = stringArray(root.path("allowedPaths"), 8, 256);
        GitHubReadmeConnectionSettings settings;
        try {
            settings = GitHubReadmeSourcePolicy.validateFirstConnection(
                    new GitHubReadmeConnectionSettings(repository, allowedPaths));
        } catch (IllegalArgumentException _) {
            throw invalidManifest();
        }

        String defaultBranch = text(root, "defaultBranch", 255);
        if (containsControl(defaultBranch)) {
            throw invalidManifest();
        }
        String commitSha = commitSha(root, "commitSha");
        Instant updatedAt = parseInstant(text(root, "updatedAt", 64));
        List<ManifestDocument> documents =
                parseDocuments(root.path("documents"), namespace, repositoryId, commitSha, settings);
        List<String> pendingDeletionIds = parsePendingDeletionIds(root.path("pendingDeletionIds"), documents);

        return new Manifest(
                repositoryId, repository, settings, defaultBranch, commitSha, documents, pendingDeletionIds, updatedAt);
    }

    String write(Manifest manifest) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 2);
        root.put("repositoryId", manifest.repositoryId());
        root.put("repository", manifest.repository());
        ArrayNode paths = root.putArray("allowedPaths");
        manifest.settings().allowedPaths().forEach(paths::add);
        root.put("defaultBranch", manifest.defaultBranch());
        root.put("commitSha", manifest.commitSha());
        ArrayNode documents = root.putArray("documents");
        for (ManifestDocument document : manifest.documents()) {
            ObjectNode entry = documents.addObject();
            entry.put("path", document.path());
            entry.put("commitSha", document.commitSha());
            entry.put("blobSha", document.blobSha());
            entry.put("documentId", document.documentId());
        }
        ArrayNode pendingDeletionIds = root.putArray("pendingDeletionIds");
        manifest.pendingDeletionIds().forEach(pendingDeletionIds::add);
        root.put("updatedAt", manifest.updatedAt().toString());
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException _) {
            throw invalidManifest();
        }
    }

    private List<ManifestDocument> parseDocuments(
            JsonNode value,
            String namespace,
            long repositoryId,
            String commitSha,
            GitHubReadmeConnectionSettings settings) {
        if (!value.isArray() || value.size() > GitHubReadmeSnapshot.MAX_DOCUMENT_COUNT) {
            throw invalidManifest();
        }
        ArrayList<ManifestDocument> documents = new ArrayList<>(value.size());
        Set<String> paths = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode document : value) {
            requireFields(document, Set.of("path", "commitSha", "blobSha", "documentId"));
            String path = text(document, "path", 1024);
            String documentCommit = commitSha(document, "commitSha");
            String blobSha = commitSha(document, "blobSha");
            String documentId = text(document, "documentId", 128);
            if (!GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, path)
                    || !commitSha.equals(documentCommit)
                    || !GitHubReadmeIndexKeys.documentId(namespace, repositoryId, path, documentCommit)
                            .equals(documentId)
                    || !paths.add(path)
                    || !ids.add(documentId)) {
                throw invalidManifest();
            }
            documents.add(new ManifestDocument(path, documentCommit, blobSha, documentId));
        }
        return List.copyOf(documents);
    }

    private static List<String> parsePendingDeletionIds(JsonNode value, List<ManifestDocument> documents) {
        if (!value.isArray() || value.size() > MAX_PENDING_DELETIONS) {
            throw invalidManifest();
        }
        Set<String> activeIds = new HashSet<>();
        documents.forEach(document -> activeIds.add(document.documentId()));
        Set<String> pendingIds = new HashSet<>();
        for (JsonNode idNode : value) {
            if (!idNode.isTextual()
                    || !GitHubReadmeIndexKeys.isDocumentId(idNode.textValue())
                    || activeIds.contains(idNode.textValue())
                    || !pendingIds.add(idNode.textValue())) {
                throw invalidManifest();
            }
        }
        return pendingIds.stream().sorted().toList();
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw invalidManifest();
        }
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalidManifest();
            }
            return root;
        } catch (IOException | RuntimeException _) {
            throw invalidManifest();
        }
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalidManifest();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!expected.equals(actual)) {
            throw invalidManifest();
        }
    }

    private static List<String> stringArray(JsonNode array, int maxItems, int maxChars) {
        if (!array.isArray() || array.size() > maxItems) {
            throw invalidManifest();
        }
        ArrayList<String> result = new ArrayList<>(array.size());
        for (JsonNode value : array) {
            if (!value.isTextual()
                    || value.textValue().isBlank()
                    || value.textValue().length() > maxChars) {
                throw invalidManifest();
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    static long positiveLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalidManifest();
        }
        return value.longValue();
    }

    private static String text(JsonNode object, String field, int maxChars) {
        JsonNode value = object.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maxChars) {
            throw invalidManifest();
        }
        return value.textValue();
    }

    private static String commitSha(JsonNode object, String field) {
        String value = text(object, field, 64);
        if (!value.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw invalidManifest();
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException _) {
            throw invalidManifest();
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static GitHubReadmeConnectorException invalidManifest() {
        return new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
    }

    record Manifest(
            long repositoryId,
            String repository,
            GitHubReadmeConnectionSettings settings,
            String defaultBranch,
            String commitSha,
            List<ManifestDocument> documents,
            List<String> pendingDeletionIds,
            Instant updatedAt) {
        Manifest {
            documents = List.copyOf(documents);
            pendingDeletionIds = List.copyOf(pendingDeletionIds);
        }
    }

    record ManifestDocument(String path, String commitSha, String blobSha, String documentId) {}
}
