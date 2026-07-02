package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.amsha.core.util.TemporalUtils;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.sqlite.jdbi.CheckpointJdbiDao;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class CheckpointExpiryJdbiTest {

    @Inject
    CheckpointStorePort checkpointStore;

    @Inject
    Jdbi jdbi;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "exp-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbi.useHandle(handle -> {
            handle.createUpdate(
                            "DELETE FROM reasoning_step WHERE checkpoint_id IN (SELECT checkpoint_id FROM agent_checkpoint WHERE session_id = :sid)")
                    .bind("sid", sessionId)
                    .execute();
            handle.createUpdate("DELETE FROM agent_checkpoint WHERE session_id = :sid")
                    .bind("sid", sessionId)
                    .execute();
        });
    }

    @Test
    void sessionTerminationMarksCheckpointFailed() {
        CheckpointDto initial = new CheckpointDto(
                null,
                sessionId,
                "coordinator",
                "IN_PROGRESS",
                "FULL",
                "{}",
                null,
                null,
                "test goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
        checkpointStore.save(initial);

        jdbi.useExtension(
                CheckpointJdbiDao.class,
                dao -> dao.markCheckpointsFailed(
                        sessionId, "Session terminated", TemporalUtils.formatIsoLex(Instant.now())));

        CheckpointDto checkpoint =
                checkpointStore.findBySessionAndRole(sessionId, "coordinator").orElseThrow();
        assertEquals("FAILED", checkpoint.status());
        assertEquals("Session terminated", checkpoint.failReason());
    }

    @Test
    void zombieRecoveryConvertsStaleInProgress() {
        String now = TemporalUtils.formatIsoLex(Instant.now());
        String staleTime = TemporalUtils.formatIsoLex(Instant.now().minus(1, ChronoUnit.HOURS));

        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO agent_checkpoint (version, session_id, agent_role, status, created_at, updated_at) "
                                + "VALUES (0, :sid, 'coordinator', 'IN_PROGRESS', :stale, :stale)")
                .bind("sid", sessionId)
                .bind("stale", staleTime)
                .execute());

        int recovered = jdbi.withExtension(
                CheckpointJdbiDao.class, dao -> dao.recoverInProgressZombies(now, "Zombie recovered", now));

        assertTrue(recovered >= 1, "Should recover at least 1 zombie checkpoint");

        CheckpointDto checkpoint =
                checkpointStore.findBySessionAndRole(sessionId, "coordinator").orElseThrow();
        assertEquals("FAILED", checkpoint.status());
        assertEquals("Zombie recovered", checkpoint.failReason());
    }

    @Test
    void staleApprovalCanBeClaimedAfterTimeout() {
        String staleTime = TemporalUtils.formatIsoLex(Instant.now().minus(30, ChronoUnit.SECONDS));

        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT INTO agent_checkpoint (version, session_id, agent_role, status, created_at, updated_at) "
                                + "VALUES (0, :sid, 'coordinator', 'AWAITING_APPROVAL', :stale, :stale)")
                .bind("sid", sessionId)
                .bind("stale", staleTime)
                .execute());

        boolean claimed = jdbi.withExtension(
                CheckpointJdbiDao.class,
                dao -> dao.claimForResume(
                        sessionId, "coordinator", TemporalUtils.formatIsoLex(Instant.now()), staleTime));

        assertTrue(claimed, "AWAITING_APPROVAL checkpoint should be immediately claimable");

        CheckpointDto checkpoint =
                checkpointStore.findBySessionAndRole(sessionId, "coordinator").orElseThrow();
        assertEquals("RESUMING", checkpoint.status());
    }
}
