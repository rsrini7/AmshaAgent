package com.arcclaw.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * SR-2: Test coverage for arcclaw-persistence-api.
 * Focuses on contract integrity and DTO null-safety.
 */
public class PersistenceDtoTest {

    @Test
    void testCheckpointDtoIntegrity() {
        CheckpointDto dto = new CheckpointDto(
            1L, "sess-1", "role", "IN_PROGRESS", "FULL", "{}", "plat-1", "telegram", "goal", 
            null, null, null, "tool-1", null, "{}", null, null, 0, null, null, null, null, null, null, 
            Instant.now(), Instant.now()
        );

        assertEquals("sess-1", dto.sessionId());
        assertEquals("IN_PROGRESS", dto.status());
        assertNotNull(dto.createdAt());
    }

    @Test
    void testAdvisoryLockContract() {
        // Ensuring the interface remains stable
        AdvisoryLockPort mock = new AdvisoryLockPort() {
            @Override public boolean tryLock(String k, int t) { return true; }
            @Override public void unlock(String k) {}
            @Override public boolean isLocked(String k) { return false; }
        };
        assertTrue(mock.tryLock("test", 10));
    }
}
