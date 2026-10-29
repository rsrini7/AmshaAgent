package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import com.amsha.core.audit.AuditService;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class GitHubReadmeLegacyIndexRemoverTest {
    private static final String NAMESPACE = "legacy-namespace";
    private static final String COMMIT = "1111111111111111111111111111111111111111";

    @Test
    void rejectsFractionalRepositoryIdBeforeLookingUpOrDeletingLegacyContent() {
        AuditService auditService = mock(AuditService.class);
        ArchivalMemoryStorePort store = mock(ArchivalMemoryStorePort.class);
        GitHubReadmeLegacyIndexRemover remover = new GitHubReadmeLegacyIndexRemover(auditService, new ObjectMapper());
        String documentId = GitHubReadmeIndexKeys.legacyDocumentId(NAMESPACE, 42, "README.md");
        String manifestContent = "{"
                + "\"repositoryId\":42.5,"
                + "\"repositoryFullName\":\"rsrini7/rsrini7\","
                + "\"path\":\"README.md\","
                + "\"defaultBranch\":\"main\","
                + "\"commitSha\":\"" + COMMIT + "\","
                + "\"documentId\":\"" + documentId + "\","
                + "\"updatedAt\":\"2026-10-29T00:00:00Z\"} ";
        ArchivalChunkDto manifest = new ArchivalChunkDto(
                "legacy-manifest-id",
                NAMESPACE,
                manifestContent,
                "FACT",
                "github-readme-index-manifest",
                COMMIT,
                "[]",
                1.0,
                0,
                Instant.now(),
                Instant.now());

        assertThrows(
                GitHubReadmeConnectorException.class,
                () -> remover.remove(NAMESPACE, "owner-fixture", manifest, store));

        verify(store, never()).getById(anyString());
        verify(store, never()).delete(anyString());
        verifyNoInteractions(auditService);
    }
}
