package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.VersionConflictException;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteCoreMemoryStoreTest {

    @Inject
    @TieredBackend("sqlite")
    SqliteCoreMemoryStore store;

    @Test
    void testCoreMemoryStoreOperations() {
        String userId = "user-" + UUID.randomUUID();
        String blockKey = "persona";
        String content = "Core memory content";

        // Read empty
        List<CoreMemoryBlockDto> emptyRead = store.read(userId);
        assertTrue(emptyRead.isEmpty());

        // Initial upsert (expectedVersion = 0)
        store.upsert(userId, blockKey, content, 0);

        Optional<CoreMemoryBlockDto> blockOpt = store.get(userId, blockKey);
        assertTrue(blockOpt.isPresent());
        CoreMemoryBlockDto block = blockOpt.get();
        assertEquals(userId, block.userId());
        assertEquals(blockKey, block.blockKey());
        assertEquals(content, block.content());
        assertEquals(1, block.version());

        // Read user blocks
        List<CoreMemoryBlockDto> userBlocks = store.read(userId);
        assertEquals(1, userBlocks.size());

        // Update with expectedVersion = 1
        store.upsert(userId, blockKey, "Updated content", 1);
        CoreMemoryBlockDto updatedBlock = store.get(userId, blockKey).orElseThrow();
        assertEquals("Updated content", updatedBlock.content());
        assertEquals(2, updatedBlock.version());

        // Version conflict test (wrong expectedVersion)
        assertThrows(VersionConflictException.class, () -> store.upsert(userId, blockKey, "Conflict content", 1));

        // Delete block
        store.delete(userId, blockKey);
        assertTrue(store.get(userId, blockKey).isEmpty());
    }
}
