package com.arcclaw.core.state;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CheckpointTransitionServiceTest {

    private static final Long CP_ID = 42L;
    private static final String SESSION_ID = "sess-abc";
    private static final String AGENT_ROLE = "coder";
    private static final String USER_ID = "user-1";
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    CheckpointStorePort storeAdapter;

    @Mock
    AuditService auditService;

    @InjectMocks
    CheckpointTransitionService service;

    private CheckpointDto buildDto() {
        return new CheckpointDto(
                CP_ID,
                SESSION_ID,
                AGENT_ROLE,
                "IN_PROGRESS",
                "FULL",
                "{}",
                "chat-1",
                "telegram",
                "do something",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                CREATED_AT,
                Instant.now());
    }

    // ── emitCreated ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("emitCreated records CHECKPOINT_CREATED audit with idempotency key")
    void emitCreatedRecordsAuditEvent() {
        service.emitCreated(SESSION_ID, AGENT_ROLE, USER_ID, AutonomyLevel.FULL, CP_ID, CREATED_AT);

        String expectedKey = "CP_CREATED:" + CP_ID + ":" + CREATED_AT.toEpochMilli();
        verify(auditService)
                .recordWithIdempotency(
                        SESSION_ID,
                        USER_ID,
                        AGENT_ROLE,
                        SecurityEventType.CHECKPOINT_CREATED,
                        "FULL autonomy",
                        expectedKey);
    }

    // ── markFailed ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markFailed delegates to store and emits CHECKPOINT_FAILED audit")
    void markFailedDelegatesAndAudits() {
        when(storeAdapter.findById(CP_ID)).thenReturn(Optional.of(buildDto()));

        service.markFailed(CP_ID, "timeout", "E001", "trace-1", "TimeoutException", USER_ID);

        verify(storeAdapter).markFailed(CP_ID, "timeout", "E001", "trace-1", "TimeoutException");
        String expectedKey = "CP_FAILED:" + CP_ID + ":" + CREATED_AT.toEpochMilli();
        verify(auditService)
                .recordWithIdempotency(
                        SESSION_ID,
                        USER_ID,
                        AGENT_ROLE,
                        SecurityEventType.CHECKPOINT_FAILED,
                        "reason=timeout errorCode=E001 traceId=trace-1",
                        expectedKey);
    }

    @Test
    @DisplayName("markFailed with reason-only overload fills defaults")
    void markFailedReasonOnlyOverload() {
        when(storeAdapter.findById(CP_ID)).thenReturn(Optional.of(buildDto()));

        service.markFailed(CP_ID, "oom");

        verify(storeAdapter).markFailed(CP_ID, "oom", null, null, null);
    }

    @Test
    @DisplayName("markFailed skips audit when checkpoint not found in store")
    void markFailedSkipsAuditWhenCheckpointMissing() {
        when(storeAdapter.findById(CP_ID)).thenReturn(Optional.empty());

        service.markFailed(CP_ID, "crash", null, null, null, USER_ID);

        verify(storeAdapter).markFailed(CP_ID, "crash", null, null, null);
        verify(auditService, never())
                .recordWithIdempotency(anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    // ── markOrphaned ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("markOrphaned delegates to store and emits CHECKPOINT_ORPHANED audit")
    void markOrphanedDelegatesAndAudits() {
        when(storeAdapter.findById(CP_ID)).thenReturn(Optional.of(buildDto()));

        service.markOrphaned(CP_ID, "scheduler abandoned", USER_ID);

        verify(storeAdapter).markOrphaned(CP_ID, "scheduler abandoned");
        String expectedKey = "CP_ORPHANED:" + CP_ID + ":" + CREATED_AT.toEpochMilli();
        verify(auditService)
                .recordWithIdempotency(
                        SESSION_ID,
                        USER_ID,
                        AGENT_ROLE,
                        SecurityEventType.CHECKPOINT_ORPHANED,
                        "scheduler abandoned",
                        expectedKey);
    }

    // ── markCompleted ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("markCompleted delegates, emits audit, and archives")
    void markCompletedDelegatesAuditsAndArchives() {
        when(storeAdapter.findById(CP_ID)).thenReturn(Optional.of(buildDto()));

        service.markCompleted(CP_ID, USER_ID);

        verify(storeAdapter).markCompleted(CP_ID);
        String expectedKey = "CP_COMPLETED:" + CP_ID + ":" + CREATED_AT.toEpochMilli();
        verify(auditService)
                .recordWithIdempotency(
                        SESSION_ID, USER_ID, AGENT_ROLE, SecurityEventType.CHECKPOINT_COMPLETED, null, expectedKey);
        verify(storeAdapter).archive(CP_ID);
    }

    @Test
    @DisplayName("markCompleted skips archive when checkpoint not found")
    void markCompletedSkipsArchiveWhenMissing() {
        when(storeAdapter.findById(CP_ID)).thenReturn(Optional.empty());

        service.markCompleted(CP_ID, USER_ID);

        verify(storeAdapter).markCompleted(CP_ID);
        verify(storeAdapter, never()).archive(CP_ID);
    }

    // ── emitFinalStatus ───────────────────────────────────────────────────────

    @Test
    @DisplayName("emitFinalStatus with COMPLETED emits CHECKPOINT_COMPLETED")
    void emitFinalStatusCompleted() {
        service.emitFinalStatus(SESSION_ID, AGENT_ROLE, USER_ID, CheckpointStatus.COMPLETED, CP_ID, CREATED_AT);

        String expectedKey = "CP_COMPLETED:" + CP_ID + ":" + CREATED_AT.toEpochMilli();
        verify(auditService)
                .recordWithIdempotency(
                        SESSION_ID, USER_ID, AGENT_ROLE, SecurityEventType.CHECKPOINT_COMPLETED, null, expectedKey);
    }

    @Test
    @DisplayName("emitFinalStatus with FAILED emits CHECKPOINT_FAILED")
    void emitFinalStatusFailed() {
        service.emitFinalStatus(SESSION_ID, AGENT_ROLE, USER_ID, CheckpointStatus.FAILED, CP_ID, CREATED_AT);

        String expectedKey = "CP_FAILED:" + CP_ID + ":" + CREATED_AT.toEpochMilli();
        verify(auditService)
                .recordWithIdempotency(
                        SESSION_ID, USER_ID, AGENT_ROLE, SecurityEventType.CHECKPOINT_FAILED, null, expectedKey);
    }
}
