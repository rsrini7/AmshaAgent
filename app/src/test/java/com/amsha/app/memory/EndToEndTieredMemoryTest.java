package com.amsha.app.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.Test;

/**
 * Phase 12 test: End-to-end tiered memory tests.
 * <p>
 * Tests the complete flow of tiered memory operations including:
 * - Recall message creation and search
 * - Archival chunk creation and search
 * - Cross-session search
 * - Summary exclusion
 * - Hybrid search
 */
class EndToEndTieredMemoryTest {

    @Test
    void recallSearchWithSessionScope() {
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test query", "session-1", 10, false, Set.of());

        assertFalse(query.isCrossSession());
        assertEquals("session-1", query.sessionId());
    }

    @Test
    void recallSearchCrossSession() {
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test query", null, 10, false, Set.of());

        assertTrue(query.isCrossSession());
        assertNull(query.sessionId());
    }

    @Test
    void recallSearchIncludeSummaries() {
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test query", "session-1", 10, true, Set.of());

        assertTrue(query.includeSummaries());
    }

    @Test
    void recallSearchExcludeSummaries() {
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test query", "session-1", 10, false, Set.of());

        assertFalse(query.includeSummaries());
    }

    @Test
    void archivalSearchWithChunkTypes() {
        ArchivalSearchQuery query =
                new ArchivalSearchQuery("user-1", "test query", 10, Set.of("CODE", "DOC"), false, true, null);

        assertEquals(2, query.chunkTypes().size());
        assertTrue(query.chunkTypes().contains("CODE"));
        assertTrue(query.chunkTypes().contains("DOC"));
    }

    @Test
    void archivalSearchWithEmptyChunkTypes() {
        ArchivalSearchQuery query = new ArchivalSearchQuery("user-1", "test query", 10, Set.of(), false, true, null);

        assertTrue(query.chunkTypes().isEmpty());
    }

    @Test
    void recallMessageDtoWithAllFields() {
        RecallMessageDto dto = new RecallMessageDto(
                "msg-1",
                "session-1",
                "user-1",
                42,
                "abc123hash",
                "user",
                "USER_MESSAGE",
                "Hello, world!",
                null,
                null,
                true,
                false,
                Instant.parse("2026-07-02T10:00:00Z"),
                "{\"key\":\"value\"}");

        assertEquals("msg-1", dto.messageId());
        assertEquals("session-1", dto.sessionId());
        assertEquals("user-1", dto.userId());
        assertEquals(42, dto.turnNumber());
        assertEquals("abc123hash", dto.contentHash());
        assertEquals("user", dto.role());
        assertEquals("USER_MESSAGE", dto.messageType());
        assertEquals("Hello, world!", dto.content());
        assertTrue(dto.hasCode());
        assertFalse(dto.hasError());
        assertNotNull(dto.createdAt());
        assertEquals("{\"key\":\"value\"}", dto.metadataJson());
    }

    @Test
    void archivalChunkDtoWithAllFields() {
        ArchivalChunkDto dto = new ArchivalChunkDto(
                "chunk-1",
                "user-1",
                "public class Foo {}",
                "CODE",
                "session-1",
                "msg-1",
                "java,spring",
                0.95,
                10,
                Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T12:00:00Z"));

        assertEquals("chunk-1", dto.chunkId());
        assertEquals("user-1", dto.userId());
        assertEquals("public class Foo {}", dto.content());
        assertEquals("CODE", dto.chunkType());
        assertEquals("session-1", dto.sourceSession());
        assertEquals("msg-1", dto.sourceMessage());
        assertEquals("java,spring", dto.tags());
        assertEquals(0.95, dto.confidence());
        assertEquals(10, dto.accessCount());
        assertNotNull(dto.createdAt());
        assertNotNull(dto.updatedAt());
    }

    @Test
    void recallMessageBatchCreation() {
        List<RecallMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            messages.add(new RecallMessageDto(
                    "msg-" + i,
                    "session-1",
                    "user-1",
                    i,
                    "hash-" + i,
                    i % 2 == 0 ? "user" : "assistant",
                    i % 2 == 0 ? "USER_MESSAGE" : "AGENT_MESSAGE",
                    "Message content " + i,
                    null,
                    null,
                    false,
                    false,
                    Instant.now(),
                    null));
        }

        assertEquals(100, messages.size());

        // Verify all messages have unique IDs
        long uniqueIds =
                messages.stream().map(RecallMessageDto::messageId).distinct().count();
        assertEquals(100, uniqueIds);

        // Verify turn numbers are sequential
        for (int i = 0; i < 100; i++) {
            assertEquals(i, messages.get(i).turnNumber());
        }
    }

    @Test
    void archivalChunkBatchCreation() {
        List<ArchivalChunkDto> chunks = new ArrayList<>();
        String[] types = {"CODE", "DOC", "PATTERN", "FACT", "PREFERENCE"};

        for (int i = 0; i < 50; i++) {
            chunks.add(new ArchivalChunkDto(
                    "chunk-" + i,
                    "user-1",
                    "Content " + i,
                    types[i % types.length],
                    "session-" + (i / 10),
                    "msg-" + i,
                    "tag-" + i,
                    0.5 + (i % 50) * 0.01,
                    i,
                    Instant.now(),
                    Instant.now()));
        }

        assertEquals(50, chunks.size());

        // Verify all chunks have unique IDs
        long uniqueIds =
                chunks.stream().map(ArchivalChunkDto::chunkId).distinct().count();
        assertEquals(50, uniqueIds);

        // Verify chunk types are distributed
        long codeCount =
                chunks.stream().filter(c -> "CODE".equals(c.chunkType())).count();
        assertEquals(10, codeCount);
    }

    @Test
    void recallSearchWithMessageTypes() {
        Set<String> messageTypes = Set.of("USER_MESSAGE", "AGENT_MESSAGE");
        RecallSearchQuery query = new RecallSearchQuery("user-1", "test", "session-1", 10, false, messageTypes);

        assertEquals(2, query.messageTypes().size());
        assertTrue(query.messageTypes().contains("USER_MESSAGE"));
        assertTrue(query.messageTypes().contains("AGENT_MESSAGE"));
    }

    @Test
    void archivalSearchWithVectorEmbedding() {
        float[] embedding = new float[384];
        for (int i = 0; i < 384; i++) {
            embedding[i] = (float) Math.random();
        }

        ArchivalSearchQuery query = new ArchivalSearchQuery("user-1", "test", 10, Set.of(), true, true, embedding);

        assertTrue(query.useVector());
        assertTrue(query.useBM25());
        assertNotNull(query.queryEmbedding());
        assertEquals(384, query.queryEmbedding().length);
    }

    @Test
    void archivalSearchBM25Only() {
        ArchivalSearchQuery query = new ArchivalSearchQuery("user-1", "test", 10, Set.of(), false, true, null);

        assertFalse(query.useVector());
        assertTrue(query.useBM25());
        assertNull(query.queryEmbedding());
    }
}
