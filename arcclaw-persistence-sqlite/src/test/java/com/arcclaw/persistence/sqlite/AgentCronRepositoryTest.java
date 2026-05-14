package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Inject;

import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronStorePort;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AgentCronRepositoryTest {

    @Inject
    CronStorePort cronStore;

    private AgentCronDto createDto(String userId, String status, Instant nextFireTime) {
        String id = UUID.randomUUID().toString();
        return new AgentCronDto(
            id,
            "session-" + id,
            userId,
            "chat-1",
            null,
            null,
            "0 9 * * 1",
            "UTC",
            false,
            "CRON: Test goal",
            nextFireTime,
            null,
            status,
            null,
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    void testFindDueCronsReturnsDueRows() {
        AgentCronDto dto = createDto("user-due-1", "ACTIVE",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        assertDoesNotThrow(() -> cronStore.save(dto));

        var due = cronStore.findDueCrons();
        assertTrue(due.stream().anyMatch(d -> d.id().equals(dto.id())));
    }

    @Test
    void testFindDueCronsExcludesFutureRows() {
        AgentCronDto dto = createDto("user-due-2", "ACTIVE",
                Instant.now().plus(1, ChronoUnit.HOURS));
        assertDoesNotThrow(() -> cronStore.save(dto));

        var due = cronStore.findDueCrons();
        assertFalse(due.stream().anyMatch(d -> d.id().equals(dto.id())));
    }

    @Test
    void testDeleteAllByUserId() {
        AgentCronDto dto1 = createDto("user-del-1", "ACTIVE", Instant.now());
        AgentCronDto dto2 = createDto("user-del-2", "ACTIVE", Instant.now());
        assertDoesNotThrow(() -> cronStore.save(dto1));
        assertDoesNotThrow(() -> cronStore.save(dto2));

        cronStore.deleteAllByUserId("user-del-1");

        assertTrue(cronStore.findById(dto1.id()).isEmpty(), "Deleted user's jobs must be removed");
        assertTrue(cronStore.findById(dto2.id()).isPresent(), "Other user's jobs must remain intact");
    }
}
