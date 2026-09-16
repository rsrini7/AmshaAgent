package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.util.UUID;

import com.amsha.persistence.api.ChatMemoryDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Real-database coverage for {@link SqliteChatMemoryStoreAdapter}.
 *
 * <p>The adapter previously had no test at all (0 of 11 executable lines covered) even though it
 * is the live {@code ChatMemoryStorePort} for the default {@code sqlite} backend. It is a thin
 * delegation layer over {@code ChatMemoryJdbiDao}, so a mocked DAO would prove nothing about the
 * SQL — these tests run the real statements against a Flyway-migrated SQLite database.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteChatMemoryStoreAdapterTest {

    @Inject
    SqliteChatMemoryStoreAdapter store;

    @Test
    void testSaveFindAndDeleteRoundTrip() {
        String memoryId = "mem-" + UUID.randomUUID();
        ChatMemoryDto dto = new ChatMemoryDto(memoryId, "[{\"role\":\"user\"}]", "{\"k\":\"v\"}");

        assertTrue(store.findById(memoryId).isEmpty(), "an unknown memory id must read as empty");

        store.save(dto);

        ChatMemoryDto fetched = store.findById(memoryId).orElseThrow();
        assertEquals(memoryId, fetched.memoryId());
        assertEquals(dto.messagesJson(), fetched.messagesJson());
        assertEquals(dto.metadataJson(), fetched.metadataJson());

        store.deleteById(memoryId);
        assertTrue(store.findById(memoryId).isEmpty());
    }

    @Test
    void saveUpsertsRatherThanDuplicating() {
        String memoryId = "mem-" + UUID.randomUUID();
        store.save(new ChatMemoryDto(memoryId, "[]", "{}"));

        // The DAO uses INSERT OR REPLACE, so a second save for the same id must overwrite.
        store.save(new ChatMemoryDto(memoryId, "[{\"role\":\"assistant\"}]", null));

        ChatMemoryDto fetched = store.findById(memoryId).orElseThrow();
        assertEquals("[{\"role\":\"assistant\"}]", fetched.messagesJson());
        assertNull(fetched.metadataJson());
    }

    @Test
    void deleteByIdOnAMissingRowIsANoOp() {
        // Deleting an absent id must not throw and must not disturb other rows.
        String kept = "mem-" + UUID.randomUUID();
        store.save(new ChatMemoryDto(kept, "[]", null));

        store.deleteById("mem-" + UUID.randomUUID());

        assertTrue(store.findById(kept).isPresent());
    }

    @Test
    void getTimestampsIsAlwaysEmpty() {
        String memoryId = "mem-" + UUID.randomUUID();
        store.save(new ChatMemoryDto(memoryId, "[]", null));

        // Documented limitation: the SQLite schema stores messages as one JSON blob with no
        // per-message timestamp column, so the port's timestamps are always empty — for a
        // stored id and for an unknown one alike.
        assertTrue(store.getTimestamps(memoryId).isEmpty());
        assertTrue(store.getTimestamps("mem-unknown-" + UUID.randomUUID()).isEmpty());
    }
}
