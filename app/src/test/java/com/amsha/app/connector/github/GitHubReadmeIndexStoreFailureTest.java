package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.security.CredentialDetectionResult;
import com.amsha.core.security.CredentialDetector;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.api.PersistenceBackend;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class GitHubReadmeIndexStoreFailureTest {
    private static final String REPOSITORY = "rsrini7/AmshaAgent";
    private static final String OWNER = "index-owner-fixture";
    private static final String WORKSPACE = "index-workspace-fixture";
    private static final GitHubReadmeConnectionSettings SETTINGS = GitHubReadmeSourcePolicy.firstConnection();
    private static final String FIRST_COMMIT = "1111111111111111111111111111111111111111";
    private static final String SECOND_COMMIT = "2222222222222222222222222222222222222222";
    private static final String FIRST_BLOB = "3333333333333333333333333333333333333333";
    private static final String SECOND_BLOB = "4444444444444444444444444444444444444444";

    @Test
    void partialRefreshWriteRemainsTrackedForRetrievalAndDisconnectCleanup() {
        InMemoryArchivalStore archive = new InMemoryArchivalStore();
        AuditService audit = mock(AuditService.class);
        ObjectMapper mapper = new ObjectMapper();
        CredentialDetector detector = mock(CredentialDetector.class);
        when(detector.detect(any())).thenReturn(CredentialDetectionResult.CLEAN);
        GitHubReadmeIndexStore store = new GitHubReadmeIndexStore(
                archivalStoreInstance(archive),
                audit,
                mapper,
                new GitHubReadmeManifestCodec(mapper),
                new GitHubReadmeLegacyIndexRemover(audit, mapper),
                detector,
                PersistenceBackend.SQLITE);
        String namespace = GitHubReadmeIndexStore.namespace(OWNER, WORKSPACE);

        GitHubReadmeStatusResponse initial = store.storeSnapshot(
                namespace, OWNER, SETTINGS, snapshot(FIRST_COMMIT, FIRST_BLOB, "prior-state-marker"));
        assertEquals("INDEXED", initial.state());

        archive.resetAndFailOnDocumentWrite(2);
        assertThrows(
                GitHubReadmeConnectorException.class,
                () -> store.storeSnapshot(
                        namespace, OWNER, SETTINGS, snapshot(SECOND_COMMIT, SECOND_BLOB, "next-state-marker")));

        GitHubReadmeManifestCodec.Manifest retained =
                store.readManifest(namespace, OWNER).orElseThrow();
        assertEquals(FIRST_COMMIT, retained.commitSha());
        assertEquals(2, retained.documents().size());
        assertEquals(2, retained.pendingDeletionIds().size());
        assertTrue(store.matchingDocuments(OWNER, WORKSPACE).stream()
                .anyMatch(document -> document.markdown().contains("prior-state-marker")));
        assertTrue(archive.chunks.values().stream()
                .anyMatch(chunk -> chunk.content().contains("next-state-marker")));

        assertTrue(store.removeIndex(namespace, OWNER));

        assertTrue(archive.chunks.isEmpty());
        assertFalse(store.readManifest(namespace, OWNER).isPresent());
    }

    private static GitHubReadmeSnapshot snapshot(String commit, String blob, String marker) {
        return new GitHubReadmeSnapshot(
                42,
                REPOSITORY,
                "main",
                commit,
                List.of(
                        new GitHubReadmeDocumentSnapshot("README.md", "# " + marker, blob),
                        new GitHubReadmeDocumentSnapshot("docs/architecture.md", "## " + marker, blob)));
    }

    @SuppressWarnings("unchecked")
    private static Instance<ArchivalMemoryStorePort> archivalStoreInstance(ArchivalMemoryStorePort store) {
        Instance<ArchivalMemoryStorePort> instances = mock(Instance.class);
        when(instances.select(any(Annotation[].class))).thenReturn(instances);
        when(instances.get()).thenReturn(store);
        return instances;
    }

    private static final class InMemoryArchivalStore implements ArchivalMemoryStorePort {
        private final Map<String, ArchivalChunkDto> chunks = new HashMap<>();
        private int documentWrites;
        private int failOnDocumentWrite = -1;

        @Override
        public void upsert(ArchivalChunkDto chunk) {
            if ("DOC".equals(chunk.chunkType())) {
                documentWrites++;
                if (documentWrites == failOnDocumentWrite) {
                    throw new IllegalStateException("injected archival write failure");
                }
            }
            chunks.put(chunk.chunkId(), chunk);
        }

        @Override
        public List<ArchivalChunkDto> search(ArchivalSearchQuery query) {
            return List.of();
        }

        @Override
        public Optional<ArchivalChunkDto> getById(String chunkId) {
            return Optional.ofNullable(chunks.get(chunkId));
        }

        @Override
        public void incrementAccessCount(String chunkId) {}

        @Override
        public void incrementAccessCounts(List<String> chunkIds) {}

        @Override
        public void delete(String chunkId) {
            chunks.remove(chunkId);
        }

        @Override
        public void evictLowConfidence(double threshold, int minAgeDays) {}

        private void resetAndFailOnDocumentWrite(int writeNumber) {
            documentWrites = 0;
            failOnDocumentWrite = writeNumber;
        }
    }
}
