package com.amsha.selfevolve.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExtractedMemoryTest {

    @Test
    void create_generatesIdAndTimestamp() {
        ExtractedMemory mem = ExtractedMemory.create(
                "session1",
                "user1",
                MemoryRecordType.FAILURE_PATTERN,
                "Tool 'sandbox_exec' failed 2 times",
                0.7,
                "hash123",
                List.of("e1", "e2"));

        assertNotNull(mem.id());
        assertEquals("session1", mem.sessionId());
        assertEquals("user1", mem.userId());
        assertEquals(MemoryRecordType.FAILURE_PATTERN, mem.recordType());
        assertEquals("Tool 'sandbox_exec' failed 2 times", mem.content());
        assertEquals(0.7, mem.confidence(), 0.001);
        assertEquals("hash123", mem.contentHash());
        assertEquals(2, mem.sourceEventIds().size());
        assertTrue(mem.sourceEventIds().contains("e1"));
        assertFalse(mem.createdAt().isAfter(Instant.now()));
    }

    @Test
    void compactConstructor_nullId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory(
                        null, "s1", "u1", MemoryRecordType.USER_PREFERENCE, "content", 0.5, "hash", "", Instant.now()));
    }

    @Test
    void compactConstructor_blankSessionId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory(
                        "id", "", "u1", MemoryRecordType.USER_PREFERENCE, "content", 0.5, "hash", "", Instant.now()));
    }

    @Test
    void compactConstructor_nullRecordType_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory("id", "s1", "u1", null, "content", 0.5, "hash", "", Instant.now()));
    }

    @Test
    void compactConstructor_blankContent_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory(
                        "id", "s1", "u1", MemoryRecordType.USER_PREFERENCE, "", 0.5, "hash", "", Instant.now()));
    }

    @Test
    void compactConstructor_confidenceOutOfRange_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory(
                        "id", "s1", "u1", MemoryRecordType.USER_PREFERENCE, "content", 1.5, "hash", "", Instant.now()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory(
                        "id",
                        "s1",
                        "u1",
                        MemoryRecordType.USER_PREFERENCE,
                        "content",
                        -0.1,
                        "hash",
                        "",
                        Instant.now()));
    }

    @Test
    void compactConstructor_nullCreatedAt_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtractedMemory(
                        "id", "s1", "u1", MemoryRecordType.USER_PREFERENCE, "content", 0.5, "hash", "", null));
    }

    @Test
    void allRecordTypes_exist() {
        assertEquals(13, MemoryRecordType.values().length);
        for (MemoryRecordType t : MemoryRecordType.values()) {
            assertNotNull(t);
        }
    }
}
