package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.CredentialDetector;
import com.amsha.memory.tiered.TieredBackendLiteral;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Stores complete GitHub snapshots behind a workspace-scoped, provenance-bearing manifest. */
@ApplicationScoped
public class GitHubReadmeIndexStore {
    private static final String MANIFEST_SOURCE = "github-documentation-index-manifest";
    private static final String CONTENT_SOURCE_PREFIX = "github-documentation:";
    private static final String LEGACY_MANIFEST_SOURCE = "github-readme-index-manifest";

    private final Instance<ArchivalMemoryStorePort> archivalStores;
    private final AuditService auditService;
    private final ObjectMapper mapper;
    private final GitHubReadmeManifestCodec manifestCodec;
    private final GitHubReadmeLegacyIndexRemover legacyIndexRemover;
    private final CredentialDetector credentialDetector;
    private final String persistenceBackend;

    @Inject
    public GitHubReadmeIndexStore(
            @Any Instance<ArchivalMemoryStorePort> archivalStores,
            AuditService auditService,
            ObjectMapper mapper,
            GitHubReadmeManifestCodec manifestCodec,
            GitHubReadmeLegacyIndexRemover legacyIndexRemover,
            CredentialDetector credentialDetector,
            @ConfigProperty(name = PersistenceBackend.PROPERTY, defaultValue = PersistenceBackend.SQLITE)
                    String persistenceBackend) {
        this.archivalStores = archivalStores;
        this.auditService = auditService;
        this.mapper = mapper;
        this.manifestCodec = manifestCodec;
        this.legacyIndexRemover = legacyIndexRemover;
        this.credentialDetector = credentialDetector;
        this.persistenceBackend = persistenceBackend;
    }

    static String namespace(String ownerUserId, String workspaceId) {
        try {
            return GitHubReadmeIndexKeys.userNamespace(ownerUserId, workspaceId);
        } catch (IllegalArgumentException _) {
            throw indexUnavailable();
        }
    }

    Optional<GitHubReadmeManifestCodec.Manifest> readManifest(String namespace, String ownerUserId) {
        Optional<ArchivalChunkDto> stored = archivalStore().getById(GitHubReadmeIndexKeys.manifestId(namespace));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ArchivalChunkDto chunk = stored.get();
        if (!namespace.equals(chunk.userId()) || !"FACT".equals(chunk.chunkType())) {
            throw indexUnavailable();
        }
        if (LEGACY_MANIFEST_SOURCE.equals(chunk.sourceSession())) {
            legacyIndexRemover.remove(namespace, ownerUserId, chunk, archivalStore());
            return Optional.empty();
        }
        if (!MANIFEST_SOURCE.equals(chunk.sourceSession())) {
            throw indexUnavailable();
        }
        return Optional.of(manifestCodec.parse(chunk.content(), namespace));
    }

    GitHubReadmeStatusResponse storeSnapshot(
            String namespace,
            String ownerUserId,
            GitHubReadmeConnectionSettings settings,
            GitHubReadmeSnapshot snapshot) {
        validateSnapshot(settings, snapshot);
        GitHubReadmeManifestCodec.Manifest previous =
                readManifest(namespace, ownerUserId).orElse(null);
        if (previous != null && !previous.settings().equals(settings)) {
            throw invalidSource();
        }

        Instant updatedAt = Instant.now();
        List<GitHubReadmeDocumentSnapshot> sourceDocuments = snapshot.documents().stream()
                .sorted(Comparator.comparing(GitHubReadmeDocumentSnapshot::path))
                .toList();
        List<GitHubReadmeManifestCodec.ManifestDocument> documents =
                manifestDocuments(namespace, snapshot, sourceDocuments);
        Set<String> activeIds = new HashSet<>();
        documents.forEach(document -> activeIds.add(document.documentId()));
        Set<String> previousActiveIds = new HashSet<>();
        if (previous != null) {
            previous.documents().forEach(document -> previousActiveIds.add(document.documentId()));
        }
        Set<String> previousPendingIds = previous == null ? Set.of() : Set.copyOf(previous.pendingDeletionIds());
        List<String> pendingDeletionIds = pendingDeletions(previous, activeIds);
        GitHubReadmeManifestCodec.Manifest next = new GitHubReadmeManifestCodec.Manifest(
                snapshot.repositoryId(),
                snapshot.repositoryFullName(),
                settings,
                snapshot.defaultBranch(),
                snapshot.commitSha(),
                documents,
                pendingDeletionIds,
                updatedAt);

        List<ArchivalChunkDto> contentToUpsert = contentToUpsert(
                namespace, snapshot, sourceDocuments, documents, updatedAt, previousActiveIds, previousPendingIds);
        if (!hasChanges(previous, next, contentToUpsert)) {
            return statusFor(namespace, previous);
        }
        auditMutation(ownerUserId, "refresh");
        try {
            if (!previousPendingIds.isEmpty()) {
                deletePendingDocuments(previous.pendingDeletionIds());
            }
            List<String> inFlightIds = documents.stream()
                    .map(GitHubReadmeManifestCodec.ManifestDocument::documentId)
                    .filter(id -> !previousActiveIds.contains(id))
                    .sorted()
                    .toList();
            if (!inFlightIds.isEmpty()) {
                GitHubReadmeManifestCodec.Manifest preparing = previous == null
                        ? new GitHubReadmeManifestCodec.Manifest(
                                snapshot.repositoryId(),
                                snapshot.repositoryFullName(),
                                settings,
                                snapshot.defaultBranch(),
                                snapshot.commitSha(),
                                List.of(),
                                inFlightIds,
                                updatedAt)
                        : new GitHubReadmeManifestCodec.Manifest(
                                previous.repositoryId(),
                                previous.repository(),
                                previous.settings(),
                                previous.defaultBranch(),
                                previous.commitSha(),
                                previous.documents(),
                                inFlightIds,
                                updatedAt);
                archivalStore().upsert(manifestChunk(namespace, preparing));
            }
            contentToUpsert.forEach(archivalStore()::upsert);
            archivalStore().upsert(manifestChunk(namespace, next));
            deletePendingDocuments(pendingDeletionIds);
            if (!pendingDeletionIds.isEmpty()) {
                GitHubReadmeManifestCodec.Manifest cleaned = new GitHubReadmeManifestCodec.Manifest(
                        next.repositoryId(),
                        next.repository(),
                        next.settings(),
                        next.defaultBranch(),
                        next.commitSha(),
                        next.documents(),
                        List.of(),
                        next.updatedAt());
                archivalStore().upsert(manifestChunk(namespace, cleaned));
                next = cleaned;
            }
        } catch (RuntimeException _) {
            throw indexUnavailable();
        }
        return statusFor(namespace, next);
    }

    GitHubReadmeStatusResponse statusFor(String namespace, GitHubReadmeManifestCodec.Manifest manifest) {
        List<GitHubReadmeDocumentStatusResponse> documents = new ArrayList<>();
        for (GitHubReadmeManifestCodec.ManifestDocument document : manifest.documents()) {
            if (getContent(namespace, manifest, document).isEmpty()) {
                return emptyStatus("UNAVAILABLE");
            }
            documents.add(
                    new GitHubReadmeDocumentStatusResponse(document.path(), document.commitSha(), document.blobSha()));
        }
        boolean pending = !manifest.pendingDeletionIds().isEmpty();
        String state = pending ? "CLEANUP_PENDING" : documents.isEmpty() ? "EMPTY" : "INDEXED";
        return new GitHubReadmeStatusResponse(
                state,
                !documents.isEmpty(),
                manifest.repositoryId(),
                manifest.repository(),
                manifest.settings().allowedPaths(),
                manifest.defaultBranch(),
                manifest.commitSha(),
                documents,
                pending,
                manifest.updatedAt());
    }

    List<GitHubReadmeContextMatcher.SourceDocument> matchingDocuments(String ownerUserId, String workspaceId) {
        if (ownerUserId == null || ownerUserId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            return List.of();
        }
        String namespace = namespace(ownerUserId, workspaceId);
        Optional<GitHubReadmeManifestCodec.Manifest> stored = readManifest(namespace, ownerUserId);
        if (stored.isEmpty()) {
            return List.of();
        }
        GitHubReadmeManifestCodec.Manifest manifest = stored.get();
        List<GitHubReadmeContextMatcher.SourceDocument> documents = new ArrayList<>();
        for (GitHubReadmeManifestCodec.ManifestDocument document : manifest.documents()) {
            ArchivalChunkDto content =
                    getContent(namespace, manifest, document).orElseThrow(GitHubReadmeIndexStore::indexUnavailable);
            documents.add(new GitHubReadmeContextMatcher.SourceDocument(
                    document.path(), document.commitSha(), content.content()));
        }
        return List.copyOf(documents);
    }

    boolean removeIndex(String namespace, String ownerUserId) {
        Optional<GitHubReadmeManifestCodec.Manifest> current = readManifest(namespace, ownerUserId);
        if (current.isEmpty()) {
            return false;
        }
        GitHubReadmeManifestCodec.Manifest manifest = current.get();
        Set<String> removeIds = new LinkedHashSet<>(manifest.pendingDeletionIds());
        manifest.documents().forEach(document -> removeIds.add(document.documentId()));
        GitHubReadmeManifestCodec.Manifest tombstone = new GitHubReadmeManifestCodec.Manifest(
                manifest.repositoryId(),
                manifest.repository(),
                manifest.settings(),
                manifest.defaultBranch(),
                manifest.commitSha(),
                List.of(),
                removeIds.stream().sorted().toList(),
                Instant.now());
        auditMutation(ownerUserId, "disconnect");
        archivalStore().upsert(manifestChunk(namespace, tombstone));
        deletePendingDocuments(tombstone.pendingDeletionIds());
        archivalStore().delete(GitHubReadmeIndexKeys.manifestId(namespace));
        return true;
    }

    private List<ArchivalChunkDto> contentToUpsert(
            String namespace,
            GitHubReadmeSnapshot snapshot,
            List<GitHubReadmeDocumentSnapshot> sourceDocuments,
            List<GitHubReadmeManifestCodec.ManifestDocument> documents,
            Instant updatedAt,
            Set<String> previousActiveIds,
            Set<String> previousPendingIds) {
        List<ArchivalChunkDto> result = new ArrayList<>();
        for (int index = 0; index < sourceDocuments.size(); index++) {
            GitHubReadmeDocumentSnapshot document = sourceDocuments.get(index);
            GitHubReadmeManifestCodec.ManifestDocument manifestDocument = documents.get(index);
            String documentId = manifestDocument.documentId();
            ArchivalChunkDto existing = archivalStore().getById(documentId).orElse(null);
            validateExistingContent(namespace, existing, snapshot.repositoryId());
            boolean exact =
                    existing != null && isExactContent(namespace, existing, snapshot, document, manifestDocument);
            if (previousActiveIds.contains(documentId) && existing != null && !exact) {
                throw indexUnavailable();
            }
            if (existing == null || previousPendingIds.contains(documentId) || !exact) {
                result.add(contentChunk(namespace, snapshot, document, manifestDocument, updatedAt, existing));
            }
        }
        return List.copyOf(result);
    }

    private void validateSnapshot(GitHubReadmeConnectionSettings settings, GitHubReadmeSnapshot snapshot) {
        if (snapshot == null
                || !settings.repository().equals(snapshot.repositoryFullName())
                || snapshot.documents().size() > GitHubReadmeSnapshot.MAX_DOCUMENT_COUNT) {
            throw invalidSource();
        }
        long totalBytes = 0;
        Set<String> paths = new HashSet<>();
        for (GitHubReadmeDocumentSnapshot document : snapshot.documents()) {
            if (document == null
                    || !GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, document.path())
                    || !paths.add(document.path())) {
                throw invalidSource();
            }
            int bytes = document.markdown().getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes;
            if (bytes > GitHubReadmeSnapshot.MAX_DOCUMENT_BYTES
                    || totalBytes > GitHubReadmeSnapshot.MAX_TOTAL_BYTES
                    || credentialDetected(document.markdown())) {
                throw invalidSource();
            }
        }
    }

    private boolean credentialDetected(String markdown) {
        try {
            return credentialDetector.detect(markdown).detected();
        } catch (RuntimeException _) {
            return true;
        }
    }

    private List<GitHubReadmeManifestCodec.ManifestDocument> manifestDocuments(
            String namespace, GitHubReadmeSnapshot snapshot, List<GitHubReadmeDocumentSnapshot> sourceDocuments) {
        return sourceDocuments.stream()
                .map(document -> new GitHubReadmeManifestCodec.ManifestDocument(
                        document.path(),
                        snapshot.commitSha(),
                        document.blobSha(),
                        GitHubReadmeIndexKeys.documentId(
                                namespace, snapshot.repositoryId(), document.path(), snapshot.commitSha())))
                .toList();
    }

    private static List<String> pendingDeletions(GitHubReadmeManifestCodec.Manifest previous, Set<String> activeIds) {
        if (previous == null) {
            return List.of();
        }
        Set<String> pending = new LinkedHashSet<>();
        previous.documents().stream()
                .map(GitHubReadmeManifestCodec.ManifestDocument::documentId)
                .filter(id -> !activeIds.contains(id))
                .forEach(pending::add);
        if (pending.size() > GitHubReadmeSnapshot.MAX_DOCUMENT_COUNT) {
            throw indexUnavailable();
        }
        return pending.stream().sorted().toList();
    }

    private boolean hasChanges(
            GitHubReadmeManifestCodec.Manifest previous,
            GitHubReadmeManifestCodec.Manifest next,
            List<ArchivalChunkDto> contentToUpsert) {
        return previous == null
                || !previous.repository().equals(next.repository())
                || previous.repositoryId() != next.repositoryId()
                || !previous.defaultBranch().equals(next.defaultBranch())
                || !previous.commitSha().equals(next.commitSha())
                || !previous.documents().equals(next.documents())
                || !previous.pendingDeletionIds().equals(next.pendingDeletionIds())
                || !next.pendingDeletionIds().isEmpty()
                || !contentToUpsert.isEmpty();
    }

    private ArchivalChunkDto contentChunk(
            String namespace,
            GitHubReadmeSnapshot snapshot,
            GitHubReadmeDocumentSnapshot sourceDocument,
            GitHubReadmeManifestCodec.ManifestDocument manifestDocument,
            Instant updatedAt,
            ArchivalChunkDto existing) {
        return new ArchivalChunkDto(
                manifestDocument.documentId(),
                namespace,
                sourceDocument.markdown(),
                "DOC",
                CONTENT_SOURCE_PREFIX + snapshot.repositoryId(),
                manifestDocument.commitSha(),
                writeTags(snapshot, sourceDocument),
                1.0,
                existing == null ? 0 : existing.accessCount(),
                existing == null || existing.createdAt() == null ? updatedAt : existing.createdAt(),
                updatedAt);
    }

    private boolean isExactContent(
            String namespace,
            ArchivalChunkDto existing,
            GitHubReadmeSnapshot snapshot,
            GitHubReadmeDocumentSnapshot sourceDocument,
            GitHubReadmeManifestCodec.ManifestDocument manifestDocument) {
        return namespace.equals(existing.userId())
                && manifestDocument.documentId().equals(existing.chunkId())
                && "DOC".equals(existing.chunkType())
                && (CONTENT_SOURCE_PREFIX + snapshot.repositoryId()).equals(existing.sourceSession())
                && manifestDocument.commitSha().equals(existing.sourceMessage())
                && existing.content().equals(sourceDocument.markdown())
                && existing.tags().equals(writeTags(snapshot, sourceDocument));
    }

    private ArchivalChunkDto manifestChunk(String namespace, GitHubReadmeManifestCodec.Manifest manifest) {
        return new ArchivalChunkDto(
                GitHubReadmeIndexKeys.manifestId(namespace),
                namespace,
                manifestCodec.write(manifest),
                "FACT",
                MANIFEST_SOURCE,
                manifest.commitSha(),
                "[\"source=github-documentation\"]",
                1.0,
                0,
                manifest.updatedAt(),
                manifest.updatedAt());
    }

    private String writeTags(GitHubReadmeSnapshot snapshot, GitHubReadmeDocumentSnapshot document) {
        ArrayNode tags = mapper.createArrayNode();
        tags.add("source=github-documentation");
        tags.add("repository=" + snapshot.repositoryFullName());
        tags.add("path=" + document.path());
        tags.add("branch=" + snapshot.defaultBranch());
        tags.add("commit=" + snapshot.commitSha());
        tags.add("blob=" + document.blobSha());
        return tags.toString();
    }

    private Optional<ArchivalChunkDto> getContent(
            String namespace,
            GitHubReadmeManifestCodec.Manifest manifest,
            GitHubReadmeManifestCodec.ManifestDocument document) {
        return archivalStore()
                .getById(document.documentId())
                .filter(content -> isValidContent(namespace, content, manifest, document))
                .filter(content -> !credentialDetected(content.content()));
    }

    private boolean isValidContent(
            String namespace,
            ArchivalChunkDto content,
            GitHubReadmeManifestCodec.Manifest manifest,
            GitHubReadmeManifestCodec.ManifestDocument document) {
        return namespace.equals(content.userId())
                && document.documentId().equals(content.chunkId())
                && "DOC".equals(content.chunkType())
                && (CONTENT_SOURCE_PREFIX + manifest.repositoryId()).equals(content.sourceSession())
                && document.commitSha().equals(content.sourceMessage())
                && tagList(content.tags())
                        .filter(tags -> tags.equals(List.of(
                                "source=github-documentation",
                                "repository=" + manifest.repository(),
                                "path=" + document.path(),
                                "branch=" + manifest.defaultBranch(),
                                "commit=" + document.commitSha(),
                                "blob=" + document.blobSha())))
                        .isPresent();
    }

    private Optional<List<String>> tagList(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isArray() || parser.nextToken() != null || root.size() != 6) {
                return Optional.empty();
            }
            List<String> values = new ArrayList<>(root.size());
            Set<String> unique = new HashSet<>();
            for (JsonNode value : root) {
                if (!value.isTextual() || !unique.add(value.textValue())) {
                    return Optional.empty();
                }
                values.add(value.textValue());
            }
            return Optional.of(List.copyOf(values));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    private void auditMutation(String ownerUserId, String operation) {
        auditService.recordMandatory(
                null,
                ownerUserId,
                "github-readme-connector",
                SecurityEventType.EXTERNAL_SOURCE_INDEX_MUTATED,
                "operation=" + operation + " source=github-documentation");
    }

    private void validateExistingContent(String namespace, ArchivalChunkDto existing, long repositoryId) {
        if (existing != null
                && (!namespace.equals(existing.userId())
                        || !"DOC".equals(existing.chunkType())
                        || !(CONTENT_SOURCE_PREFIX + repositoryId).equals(existing.sourceSession()))) {
            throw indexUnavailable();
        }
    }

    private void deletePendingDocuments(List<String> documentIds) {
        for (String documentId : documentIds) {
            if (!GitHubReadmeIndexKeys.isDocumentId(documentId)) {
                throw indexUnavailable();
            }
            archivalStore().delete(documentId);
        }
    }

    private ArchivalMemoryStorePort archivalStore() {
        String backend = persistenceBackend == null || persistenceBackend.isBlank()
                ? PersistenceBackend.SQLITE
                : persistenceBackend.trim().toLowerCase(Locale.ROOT);
        Instance<ArchivalMemoryStorePort> selected = archivalStores.select(new TieredBackendLiteral(backend));
        if (selected.isUnsatisfied() || selected.isAmbiguous()) {
            throw indexUnavailable();
        }
        return selected.get();
    }

    private static GitHubReadmeStatusResponse emptyStatus(String state) {
        return new GitHubReadmeStatusResponse(state, false, null, null, List.of(), null, null, List.of(), false, null);
    }

    private static GitHubReadmeConnectorException invalidSource() {
        return new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INVALID_SOURCE_RESPONSE);
    }

    private static GitHubReadmeConnectorException indexUnavailable() {
        return new GitHubReadmeConnectorException(GitHubReadmeConnectorException.Kind.INDEX_UNAVAILABLE);
    }
}
