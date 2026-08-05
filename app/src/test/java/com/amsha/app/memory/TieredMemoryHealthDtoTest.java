package com.amsha.app.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.persistence.api.TieredMemoryHealthDto;

import org.junit.jupiter.api.Test;

/**
 * Test: Verifies TieredMemoryHealthDto schema covers all three tiers.
 * <p>
 * The health DTO should provide comprehensive information about:
 * - Overall health status
 * - Backend type
 * - Core memory block count
 * - Recall message count
 * - Archival chunk count
 */
class TieredMemoryHealthDtoTest {

    @Test
    void healthDtoCreationWithAllFields() {
        TieredMemoryHealthDto dto =
                new TieredMemoryHealthDto(true, "sqlite", "All systems operational", 5, 1000L, 500L);

        assertTrue(dto.healthy());
        assertEquals("sqlite", dto.backend());
        assertEquals("All systems operational", dto.detail());
        assertEquals(5, dto.coreBlockCount());
        assertEquals(1000L, dto.recallMessageCount());
        assertEquals(500L, dto.archivalChunkCount());
    }

    @Test
    void healthDtoWithUnhealthyStatus() {
        TieredMemoryHealthDto dto =
                new TieredMemoryHealthDto(false, "postgres", "Database connection failed", 0, 0L, 0L);

        assertFalse(dto.healthy());
        assertEquals("postgres", dto.backend());
        assertEquals("Database connection failed", dto.detail());
        assertEquals(0, dto.coreBlockCount());
        assertEquals(0L, dto.recallMessageCount());
        assertEquals(0L, dto.archivalChunkCount());
    }

    @Test
    void healthDtoWithFilesystemBackend() {
        TieredMemoryHealthDto dto = new TieredMemoryHealthDto(true, "fs", "Filesystem backend active", 3, 250L, 100L);

        assertTrue(dto.healthy());
        assertEquals("fs", dto.backend());
        assertEquals("Filesystem backend active", dto.detail());
        assertEquals(3, dto.coreBlockCount());
        assertEquals(250L, dto.recallMessageCount());
        assertEquals(100L, dto.archivalChunkCount());
    }

    @Test
    void healthDtoWithZeroCounts() {
        TieredMemoryHealthDto dto = new TieredMemoryHealthDto(true, "sqlite", "No data yet", 0, 0L, 0L);

        assertTrue(dto.healthy());
        assertEquals(0, dto.coreBlockCount());
        assertEquals(0L, dto.recallMessageCount());
        assertEquals(0L, dto.archivalChunkCount());
    }

    @Test
    void healthDtoWithLargeCounts() {
        TieredMemoryHealthDto dto =
                new TieredMemoryHealthDto(true, "postgres", "Production database", 10, 1_000_000L, 500_000L);

        assertTrue(dto.healthy());
        assertEquals(10, dto.coreBlockCount());
        assertEquals(1_000_000L, dto.recallMessageCount());
        assertEquals(500_000L, dto.archivalChunkCount());
    }

    @Test
    void healthDtoCoversAllTiers() {
        // Verify the DTO has fields for all three tiers
        TieredMemoryHealthDto dto = new TieredMemoryHealthDto(
                true, "sqlite", "Test", 1, // core
                2L, // recall
                3L); // archival

        // Core tier
        assertEquals(1, dto.coreBlockCount());

        // Recall tier
        assertEquals(2L, dto.recallMessageCount());

        // Archival tier
        assertEquals(3L, dto.archivalChunkCount());
    }

    @Test
    void healthDtoEquality() {
        TieredMemoryHealthDto dto1 = new TieredMemoryHealthDto(true, "sqlite", "Test", 5, 100L, 50L);

        TieredMemoryHealthDto dto2 = new TieredMemoryHealthDto(true, "sqlite", "Test", 5, 100L, 50L);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    void healthDtoToString() {
        TieredMemoryHealthDto dto = new TieredMemoryHealthDto(true, "sqlite", "Test", 5, 100L, 50L);

        String toString = dto.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("sqlite"));
        assertTrue(toString.contains("Test"));
    }
}
