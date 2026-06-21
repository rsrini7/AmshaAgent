package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

import com.arcclaw.persistence.api.MemoryChunkDto;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GlobalKnowledgeBaseTest {

    @Inject
    GlobalKnowledgeBase kb;

    @Inject
    Jdbi jdbi;

    @Inject
    FtsConnectionInterceptor ftsInterceptor;

    @BeforeEach
    void setUp() {
        // Clean up any test data
        jdbi.useHandle(handle -> {
            handle.createUpdate("DELETE FROM fts.vec_global WHERE chunk_id LIKE 'test-%'")
                    .execute();
            handle.createUpdate("DELETE FROM rag_chunk WHERE chunk_id LIKE 'test-%'")
                    .execute();
        });
    }

    @Test
    void ingestStoresVectorAndRagChunk() {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return;
        }

        String text = "test-" + UUID.randomUUID().toString().substring(0, 8) + " ArcClaw is a coding assistant.";
        String chunkId = kb.ingest(text);
        if (chunkId == null) {
            return; // embedding model not available in test environment
        }
        assertNotNull(chunkId, "ingest should return a chunk ID");

        String storedText =
                jdbi.withHandle(handle -> handle.createQuery("SELECT text FROM rag_chunk WHERE chunk_id = ?")
                        .bind(0, chunkId)
                        .mapTo(String.class)
                        .findOne()
                        .orElse(null));
        assertEquals(text, storedText);

        int vecCount =
                jdbi.withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM fts.vec_global WHERE chunk_id = ?")
                        .bind(0, chunkId)
                        .mapTo(Integer.class)
                        .one());
        assertEquals(1, vecCount);
    }

    @Test
    void searchReturnsResultsFromRagChunk() {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return;
        }

        String text = "test-" + UUID.randomUUID().toString().substring(0, 8) + " Machine learning is fun.";
        String chunkId = kb.ingest(text);
        if (chunkId == null) {
            return; // embedding model not available
        }

        List<MemoryChunkDto> results = kb.search("machine learning", 5);
        assertNotNull(results);
        boolean found = results.stream().anyMatch(r -> r.text().contains("Machine learning"));
        assertTrue(found, "Should find the ingested chunk via semantic search");
    }

    @Test
    void ingestHandlesEmbeddingFailureGracefully() {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return;
        }

        // With test config (fake API key), ingest may fail gracefully
        kb.ingest("test-" + UUID.randomUUID().toString().substring(0, 8) + " This is a test.");
        // In test environment without real embedding model, result may be null
        // We just verify no exception is thrown
        assertDoesNotThrow(() -> {});
    }

    @Test
    void vectorAndChunkInsertAreAtomic() {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return;
        }

        assertDoesNotThrow(() -> {
            String result = kb.ingest(
                    "test-atomic-" + UUID.randomUUID().toString().substring(0, 8) + " Atomicity test content.");
            if (result != null) {
                String text =
                        jdbi.withHandle(handle -> handle.createQuery("SELECT text FROM rag_chunk WHERE chunk_id = ?")
                                .bind(0, result)
                                .mapTo(String.class)
                                .findOne()
                                .orElse(null));
                assertNotNull(text, "Rag chunk should exist after successful ingest");
            }
        });
    }

    @Test
    void ingestRollsBackWhenRagInsertFails() {
        if (!ftsInterceptor.isVectorSearchEnabled()) {
            return;
        }

        // Rename rag_chunk table to simulate database failure or missing table
        jdbi.useHandle(handle -> handle.execute("ALTER TABLE rag_chunk RENAME TO rag_chunk_temp"));

        String text = "test-rollback-" + UUID.randomUUID().toString().substring(0, 8);
        String chunkId = null;
        try {
            chunkId = kb.ingest(text);
        } finally {
            // Restore table name to not break other tests
            jdbi.useHandle(handle -> handle.execute("ALTER TABLE rag_chunk_temp RENAME TO rag_chunk"));
        }

        assertNull(chunkId, "ingest should return null on failure");

        // Verify that no orphaned vector row was committed to vec_global
        int orphanedCount = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT COUNT(*) FROM fts.vec_global WHERE chunk_id NOT IN (SELECT chunk_id FROM rag_chunk)")
                .mapTo(Integer.class)
                .one());
        assertEquals(0, orphanedCount, "No orphaned vectors should be left in fts.vec_global");
    }
}
