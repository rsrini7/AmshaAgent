package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteRecallMemoryStoreTest {

    @Inject
    @TieredBackend("sqlite")
    SqliteRecallMemoryStore store;

    @Test
    void testRecallMemoryStoreOperations() {
        String userId = "user-recall-" + UUID.randomUUID();
        String sessionId = "session-recall-" + UUID.randomUUID();
        String messageId = "msg-" + UUID.randomUUID();

        RecallMessageDto dto = new RecallMessageDto(
                messageId,
                sessionId,
                userId,
                1,
                "hash-123",
                "user",
                "USER_MESSAGE",
                "Hello quantum computing world",
                null,
                null,
                false,
                false,
                Instant.now(),
                "{}");

        store.insertOrIgnore(dto);

        Optional<RecallMessageDto> retrievedOpt = store.getById(messageId);
        assertTrue(retrievedOpt.isPresent());
        RecallMessageDto retrieved = retrievedOpt.get();
        assertEquals(messageId, retrieved.messageId());
        assertEquals(sessionId, retrieved.sessionId());
        assertEquals(userId, retrieved.userId());
        assertEquals("Hello quantum computing world", retrieved.content());

        assertEquals(1, store.countByUserId(userId));

        // Insert batch
        String msg2 = "msg2-" + UUID.randomUUID();
        RecallMessageDto dto2 = new RecallMessageDto(
                msg2,
                sessionId,
                userId,
                2,
                "hash-456",
                "assistant",
                "AGENT_MESSAGE",
                "Quantum computing response",
                null,
                null,
                false,
                false,
                Instant.now(),
                "{}");
        store.insertBatch(List.of(dto2));
        assertEquals(2, store.countByUserId(userId));

        // Search query
        RecallSearchQuery query = new RecallSearchQuery(userId, "quantum", sessionId, 10, false, null);
        List<RecallMessageDto> searchResults = store.search(query);
        assertNotNull(searchResults);

        // Purge expired test
        store.purgeExpired(userId, 0);
        store.purgeExpiredAll(0);
    }
}
