package com.amsha.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CronRunDto;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CronFailureCleanupTest {

    private static final String CRON_ID = "cron-123";

    @Mock
    private StatefulAgentStore agentStore;

    @Mock
    private CronStorePort cronStore;

    @Mock
    private CronRunStorePort cronRunStore;

    private CronFailureCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new CronFailureCleanup();
        cleanup.agentStore = agentStore;
        cleanup.cronStore = cronStore;
        cleanup.cronRunStore = cronRunStore;
    }

    @Test
    void staleApprovalWithoutCheckpointIsFailed() {
        AgentCronDto cron = cron(CRON_ID, null, CronStatus.AWAITING_APPROVAL.name(), "researcher");
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(cron));
        when(agentStore.load("cron-" + CRON_ID, "researcher")).thenReturn(Optional.empty());

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronStore).markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString());
    }

    @Test
    void liveApprovalCheckpointIsPreserved() {
        AgentCronDto cron = cron(CRON_ID, "delivery-1", CronStatus.AWAITING_APPROVAL.name(), "researcher");
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(cron));
        when(agentStore.load("cron-" + CRON_ID + "-delivery-1", "researcher")).thenReturn(Optional.of(checkpoint));

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
    }

    private static AgentCronDto cron(String id, String deliveryId, String status, String executionRole) {
        Instant stale = Instant.now().minus(25, ChronoUnit.HOURS);
        return new AgentCronDto(
                id,
                "origin-session",
                "user-1",
                "chat-1",
                "WEBSOCKET",
                "platform-1",
                "0 0 * * *",
                "UTC",
                true,
                "goal",
                stale,
                null,
                status,
                executionRole,
                null,
                deliveryId,
                stale,
                stale);
    }

    // ================= sweepOrphanedApprovals: remaining branches =================

    /** A valid 36-char job id - parseSessionId requires the suffix to be at least this long. */
    private static final String JOB_ID = "a".repeat(36);

    /**
     * Flexible factory. The two-arg {@code cron(...)} helper above is left untouched so the original
     * assertions keep their exact semantics; this overload just exposes runOnce / cronExpression /
     * timestamps, which the sweep tests need to steer individual branches.
     */
    private static AgentCronDto cronWith(
            String id,
            String status,
            boolean runOnce,
            String cronExpression,
            String deliveryId,
            String executionRole,
            Instant createdAt,
            Instant updatedAt) {
        return new AgentCronDto(
                id,
                "origin-session",
                "user-1",
                "chat-1",
                "WEBSOCKET",
                "platform-1",
                cronExpression,
                "UTC",
                runOnce,
                "goal",
                null,
                null,
                status,
                executionRole,
                null,
                deliveryId,
                createdAt,
                updatedAt);
    }

    private static CronRunDto run(String id) {
        Instant started = Instant.now().minus(1, ChronoUnit.HOURS);
        return new CronRunDto(
                id, CRON_ID, "session-x", "RUNNING", started, null, null, 3, 10, 20, BigDecimal.ONE, started);
    }

    private static AgentCronDto staleAwaiting(String deliveryId, String executionRole) {
        Instant stale = Instant.now().minus(25, ChronoUnit.HOURS);
        return cronWith(
                CRON_ID,
                CronStatus.AWAITING_APPROVAL.name(),
                true,
                "0 0 * * *",
                deliveryId,
                executionRole,
                stale,
                stale);
    }

    @Test
    void freshApprovalIsNotSwept() {
        Instant now = Instant.now();
        when(cronStore.findAllAwaitingApproval())
                .thenReturn(List.of(cronWith(
                        CRON_ID,
                        CronStatus.AWAITING_APPROVAL.name(),
                        true,
                        "0 0 * * *",
                        null,
                        "researcher",
                        now,
                        now)));

        cleanup.sweepOrphanedApprovals(now.minus(24, ChronoUnit.HOURS), 24);

        // Not yet past the cutoff: the checkpoint is never even loaded.
        verifyNoInteractions(agentStore);
        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
    }

    @Test
    void approvalWithNoTimestampAtAllIsSkipped() {
        // Both updatedAt and createdAt null -> `reference == null` -> continue.
        when(cronStore.findAllAwaitingApproval())
                .thenReturn(List.of(cronWith(
                        CRON_ID,
                        CronStatus.AWAITING_APPROVAL.name(),
                        true,
                        "0 0 * * *",
                        null,
                        "researcher",
                        null,
                        null)));

        cleanup.sweepOrphanedApprovals(Instant.now(), 24);

        verifyNoInteractions(agentStore);
        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
    }

    @Test
    void staleApprovalFallsBackToCreatedAtWhenUpdatedAtIsNull() {
        Instant stale = Instant.now().minus(25, ChronoUnit.HOURS);
        when(cronStore.findAllAwaitingApproval())
                .thenReturn(List.of(cronWith(
                        CRON_ID,
                        CronStatus.AWAITING_APPROVAL.name(),
                        true,
                        "0 0 * * *",
                        null,
                        "researcher",
                        stale,
                        null)));
        when(agentStore.load("cron-" + CRON_ID, "researcher")).thenReturn(Optional.empty());

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronStore).markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString());
    }

    @Test
    void staleApprovalWithNonAwaitingCheckpointIsFailed() {
        // A checkpoint exists but is NOT awaiting approval (e.g. FAILED) -> the cron is orphaned.
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.FAILED);
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting(null, "researcher")));
        when(agentStore.load("cron-" + CRON_ID, "researcher")).thenReturn(Optional.of(checkpoint));

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronStore).markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString());
    }

    @Test
    void sweptApprovalAlsoFailsItsLastRun() {
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting("delivery-9", "researcher")));
        when(agentStore.load("cron-" + CRON_ID + "-delivery-9", "researcher")).thenReturn(Optional.empty());
        when(cronStore.markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString()))
                .thenReturn(true);
        when(cronRunStore.findById("delivery-9")).thenReturn(Optional.of(run("delivery-9")));

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        ArgumentCaptor<CronRunDto> saved = ArgumentCaptor.forClass(CronRunDto.class);
        verify(cronRunStore).save(saved.capture());
        CronRunDto dto = saved.getValue();
        assertEquals("FAILED", dto.status());
        assertTrue(dto.errorReason().contains("timed out"), "reason should be propagated to the run");
        assertNotNull(dto.finishedAt(), "a terminal run must get a finishedAt timestamp");
        // Immutable fields carried over from the existing row.
        assertEquals("delivery-9", dto.id());
        assertEquals(CRON_ID, dto.cronId());
        assertEquals(3, dto.iterationsUsed());
        assertEquals(BigDecimal.ONE, dto.usdCost());
    }

    @Test
    void noRunUpdateWhenCronStatusChangedUnderneath() {
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting("delivery-9", "researcher")));
        when(agentStore.load("cron-" + CRON_ID + "-delivery-9", "researcher")).thenReturn(Optional.empty());
        // Lost the race: another node already transitioned the cron, so nothing was marked.
        when(cronStore.markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString()))
                .thenReturn(false);

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronRunStore, never()).findById(anyString());
        verify(cronRunStore, never()).save(any());
    }

    @Test
    void approvalLookupDefaultsNullRoleToCoordinator() {
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting(null, null)));
        when(agentStore.load("cron-" + CRON_ID, "coordinator")).thenReturn(Optional.empty());

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(agentStore).load("cron-" + CRON_ID, "coordinator");
    }

    @Test
    void approvalLookupDefaultsBlankRoleToCoordinator() {
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting(null, "   ")));
        when(agentStore.load("cron-" + CRON_ID, "coordinator")).thenReturn(Optional.empty());

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(agentStore).load("cron-" + CRON_ID, "coordinator");
    }

    @Test
    void blankDeliveryIdIsNotAppendedToSessionId() {
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting("   ", "researcher")));
        when(agentStore.load("cron-" + CRON_ID, "researcher")).thenReturn(Optional.empty());

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(agentStore).load("cron-" + CRON_ID, "researcher");
    }

    // ================= cleanupCronOnFailure: session-id parsing =================

    @Test
    void nullSessionIdIsIgnored() {
        cleanup.cleanupCronOnFailure(null, "boom");

        verifyNoInteractions(cronStore, cronRunStore);
    }

    @Test
    void nonCronSessionIdIsIgnored() {
        cleanup.cleanupCronOnFailure("session-abc", "boom");

        verifyNoInteractions(cronStore, cronRunStore);
    }

    @Test
    void tooShortCronSessionIdIsIgnored() {
        cleanup.cleanupCronOnFailure("cron-short", "boom");

        verifyNoInteractions(cronStore, cronRunStore);
    }

    @Test
    void cronSessionIdWithoutExecutionIdSkipsRunUpdate() {
        // Suffix is exactly 36 chars -> a valid job id with no execution id.
        cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom");

        verify(cronRunStore, never()).findById(anyString());
        verify(cronStore).findById(JOB_ID);
    }

    @Test
    void malformedSeparatorInSessionIdIsIgnored() {
        // 37th char is not '-' -> malformed, warn and bail out.
        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "Xmore", "boom");

        verifyNoInteractions(cronStore, cronRunStore);
    }

    @Test
    void sessionIdWithTrailingSeparatorOnlyIsIgnored() {
        // Separator present but nothing after it -> no execution id, treated as malformed.
        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "-", "boom");

        verifyNoInteractions(cronStore, cronRunStore);
    }

    @Test
    void executionIdTriggersRunFailure() {
        when(cronRunStore.findById("exec-1")).thenReturn(Optional.of(run("exec-1")));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "-exec-1", "boom");

        ArgumentCaptor<CronRunDto> saved = ArgumentCaptor.forClass(CronRunDto.class);
        verify(cronRunStore).save(saved.capture());
        assertEquals("FAILED", saved.getValue().status());
        assertEquals("boom", saved.getValue().errorReason());
    }

    // ================= cleanupCronOnFailure: status transitions =================

    private static AgentCronDto job(String status, boolean runOnce, String cronExpression, String deliveryId) {
        return cronWith(JOB_ID, status, runOnce, cronExpression, deliveryId, "coordinator", null, null);
    }

    @Test
    void runningRecurringCronWithoutExecutionIdIsMarkedFailed() {
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.RUNNING.name(), false, "0 0 * * *", null)));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom");

        verify(cronStore).markFailedIfStatus(JOB_ID, CronStatus.RUNNING.name(), "boom");
    }

    @Test
    void runningRecurringCronOwnedByExecutionIsRescheduled() {
        // Recurring cron whose RUNNING claim belongs to this execution: it is re-fired, not failed.
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.RUNNING.name(), false, "0 0 * * *", "exec-1")));
        when(cronStore.markFiredIfOwner(eq(JOB_ID), eq("exec-1"), any(Instant.class), any(Instant.class)))
                .thenReturn(true);

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "-exec-1", "boom");

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(cronStore).markFiredIfOwner(eq(JOB_ID), eq("exec-1"), any(Instant.class), next.capture());
        // The reschedule must be in the future - a next-fire in the past would spin the scheduler.
        assertTrue(next.getValue().isAfter(Instant.now()), "rescheduled next fire time should be in the future");
        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
    }

    @Test
    void runningCronOwnedByAnotherExecutionIsSkipped() {
        // Claim belongs to a different execution -> this stale session must not touch it.
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.RUNNING.name(), true, "0 0 * * *", "other")));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "-exec-1", "boom");

        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
        verify(cronStore, never()).markFailedIfOwner(anyString(), anyString(), anyString());
        verify(cronStore, never()).markFiredIfOwner(anyString(), anyString(), any(), any());
    }

    @Test
    void runningOneShotCronOwnedByExecutionIsMarkedFailedByOwner() {
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.RUNNING.name(), true, "0 0 * * *", "exec-1")));
        when(cronStore.markFailedIfOwner(eq(JOB_ID), eq("exec-1"), eq("boom"))).thenReturn(true);

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "-exec-1", "boom");

        verify(cronStore).markFailedIfOwner(JOB_ID, "exec-1", "boom");
    }

    @Test
    void runningCronWithBlankScheduleIsMarkedFailedByStatus() {
        // runOnce=false but no usable schedule -> cannot be rescheduled, so it fails outright.
        when(cronStore.findById(JOB_ID)).thenReturn(Optional.of(job(CronStatus.RUNNING.name(), false, "   ", null)));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom");

        verify(cronStore).markFailedIfStatus(JOB_ID, CronStatus.RUNNING.name(), "boom");
    }

    @Test
    void awaitingApprovalCronIsMarkedFailed() {
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.AWAITING_APPROVAL.name(), true, "0 0 * * *", null)));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom");

        // The expected status must be the cron's own current status, not a hardcoded constant.
        verify(cronStore).markFailedIfStatus(JOB_ID, CronStatus.AWAITING_APPROVAL.name(), "boom");
    }

    @Test
    void awaitingRetryCronIsMarkedFailed() {
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.AWAITING_RETRY.name(), true, "0 0 * * *", null)));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom");

        verify(cronStore).markFailedIfStatus(JOB_ID, CronStatus.AWAITING_RETRY.name(), "boom");
    }

    @Test
    void terminalCronIsSkipped() {
        // COMPLETED is neither owner nor pending: nothing to reap.
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.COMPLETED.name(), true, "0 0 * * *", null)));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom");

        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
        verify(cronStore, never()).markFailedIfOwner(anyString(), anyString(), anyString());
    }

    @Test
    void lostRaceOnPendingCronDoesNotThrow() {
        // Conditional update returned false (someone else won) -> logs CRON-REAP-SKIP and returns.
        when(cronStore.findById(JOB_ID))
                .thenReturn(Optional.of(job(CronStatus.AWAITING_APPROVAL.name(), true, "0 0 * * *", null)));
        when(cronStore.markFailedIfStatus(eq(JOB_ID), eq(CronStatus.AWAITING_APPROVAL.name()), eq("boom")))
                .thenReturn(false);

        assertDoesNotThrow(() -> cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom"));

        verify(cronStore).markFailedIfStatus(JOB_ID, CronStatus.AWAITING_APPROVAL.name(), "boom");
    }

    @Test
    void storeFailureIsSwallowed() {
        // Cleanup runs during failure handling: a store outage must never escalate.
        when(cronStore.findById(JOB_ID)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> cleanup.cleanupCronOnFailure("cron-" + JOB_ID, "boom"));
    }

    @Test
    void missingCronRowStopsAfterRunUpdate() {
        // Execution id is present so the run is failed, but the cron row is gone -> early return.
        when(cronStore.findById(JOB_ID)).thenReturn(Optional.empty());
        when(cronRunStore.findById("exec-1")).thenReturn(Optional.of(run("exec-1")));

        cleanup.cleanupCronOnFailure("cron-" + JOB_ID + "-exec-1", "boom");

        verify(cronRunStore).save(any(CronRunDto.class));
        verify(cronStore, never()).markFailedIfStatus(anyString(), anyString(), anyString());
    }

    @Test
    void sweepWithoutDeliveryIdSkipsRunUpdate() {
        // lastDeliveryId null -> markRunFailed's blank guard returns before touching the run store.
        when(cronStore.findAllAwaitingApproval()).thenReturn(List.of(staleAwaiting(null, "researcher")));
        when(agentStore.load("cron-" + CRON_ID, "researcher")).thenReturn(Optional.empty());
        when(cronStore.markFailedIfStatus(eq(CRON_ID), eq(CronStatus.AWAITING_APPROVAL.name()), anyString()))
                .thenReturn(true);

        cleanup.sweepOrphanedApprovals(Instant.now().minus(24, ChronoUnit.HOURS), 24);

        verify(cronRunStore, never()).findById(anyString());
        verify(cronRunStore, never()).save(any());
    }
}
