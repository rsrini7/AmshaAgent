package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Removes the superseded profile-README index before the new source is connected. */
@ApplicationScoped
public class GitHubReadmeLegacyIndexRemover {
    private static final String LEGACY_CONTENT_SOURCE_PREFIX = "github-readme:";

    private final AuditService auditService;
    private final ObjectMapper mapper;

    @Inject
    public GitHubReadmeLegacyIndexRemover(AuditService auditService, ObjectMapper mapper) {
        this.auditService = auditService;
        this.mapper = mapper;
    }

    void remove(
            String namespace,
            String ownerUserId,
            ArchivalChunkDto legacyManifest,
            ArchivalMemoryStorePort archivalStore) {
        LegacyManifest manifest = parseManifest(legacyManifest.content(), namespace);
        Optional<ArchivalChunkDto> document = archivalStore.getById(manifest.documentId());
        if (document.isPresent() && !isLegacyContent(namespace, document.get(), manifest)) {
            throw indexUnavailable();
        }
        auditService.recordMandatory(
                null,
                ownerUserId,
                "github-readme-connector",
                SecurityEventType.EXTERNAL_SOURCE_INDEX_MUTATED,
                "operation=remove_superseded_profile_source source=github-documentation");
        document.ifPresent(value -> archivalStore.delete(value.chunkId()));
        archivalStore.delete(legacyManifest.chunkId());
    }

    private LegacyManifest parseManifest(String json, String namespace) {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw indexUnavailable();
            }
            Set<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(Set.of(
                    "repositoryId",
                    "repositoryFullName",
                    "path",
                    "defaultBranch",
                    "commitSha",
                    "documentId",
                    "updatedAt"))) {
                throw indexUnavailable();
            }
            long repositoryId = GitHubReadmeManifestCodec.positiveLong(root, "repositoryId");
            String repository = root.path("repositoryFullName").textValue();
            String path = root.path("path").textValue();
            String branch = root.path("defaultBranch").textValue();
            String commit = root.path("commitSha").textValue();
            String documentId = root.path("documentId").textValue();
            Instant.parse(root.path("updatedAt").textValue());
            if (repositoryId <= 0
                    || !"rsrini7/rsrini7".equals(repository)
                    || !"README.md".equals(path)
                    || branch == null
                    || branch.isBlank()
                    || commit == null
                    || !commit.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")
                    || !GitHubReadmeIndexKeys.legacyDocumentId(namespace, repositoryId, path)
                            .equals(documentId)) {
                throw indexUnavailable();
            }
            return new LegacyManifest(repositoryId, commit.toLowerCase(Locale.ROOT), documentId);
        } catch (IOException | RuntimeException _) {
            throw indexUnavailable();
        }
    }

    private static boolean isLegacyContent(String namespace, ArchivalChunkDto content, LegacyManifest manifest) {
        return namespace.equals(content.userId())
                && "DOC".equals(content.chunkType())
                && (LEGACY_CONTENT_SOURCE_PREFIX + manifest.repositoryId()).equals(content.sourceSession())
                && manifest.commitSha().equals(content.sourceMessage())
                && content.tags() != null
                && content.tags().contains("path=README.md");
    }

    private static GitHubReadmeConnectorException indexUnavailable() {
        return new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
    }

    private record LegacyManifest(long repositoryId, String commitSha, String documentId) {}
}
