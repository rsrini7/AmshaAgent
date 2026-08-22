package com.amsha.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;

import org.jboss.logging.Logger;

/**
 * Encapsulates checkpoint status transition rules and the corresponding
 * audit event emissions for all terminal state changes.
 *
 * <p><strong>Transaction contract (§5.8 MUST):</strong> This class carries NO
 * {@code @Transactional} annotation. The {@link CheckpointStorePort} methods
 * it calls ({@code markFailed}, {@code markCompleted}, {@code markOrphaned})
 * manage their own transactional boundaries. Adding {@code REQUIRED} here
 * would conflict with their {@code REQUIRES_NEW} scopes and reintroduce the
 * P84 SQLITE_BUSY cascade.
 *
 * <p><strong>§5.18 (MUST):</strong> Terminal status exclusion (FAILED,
 * COMPLETED, ARCHIVED, COMPROMISED) from the FAILED overwrite is enforced
 * by the SQLite adapter's UPDATE WHERE clause. Do not duplicate that guard here.
 *
 * <p><strong>§5.19 (MUST):</strong> The adapter uses {@code ORDER BY id DESC LIMIT 1}
 * for the most-recent checkpoint lookup. Do not alter query ordering when
 * fetching by ID for audit purposes.
 */
@ApplicationScoped
public class CheckpointTransitionService {

    private static final Logger log = Logger.getLogger(CheckpointTransitionService.class);

    @Inject
    CheckpointStorePort storeAdapter;

    @Inject
    AuditService auditService;

    // ── CHECKPOINT_CREATED ────────────────────────────────────────────────────

    /**
     * Emits a CHECKPOINT_CREATED audit event for a brand-new checkpoint.
     * Called by {@link StatefulAgentStoreImpl} when {@code upsertWithSteps} returns
     * {@code created == true}.
     *
     * @param sessionId the session ID
     * @param agentRole the agent role
     * @param userId    the user ID (already resolved by the caller to avoid
     *                  re-reading MDC after scope handoff)
     * @param autonomy  the autonomy level stamped on the checkpoint
     * @param checkpointId the new checkpoint ID
     * @param createdAt   the creation time
     */
    public void emitCreated(
            String sessionId,
            String agentRole,
            String userId,
            AutonomyLevel autonomy,
            Long checkpointId,
            Instant createdAt) {
        String idempotencyKey = "CP_CREATED:" + checkpointId + ":" + createdAt.toEpochMilli();
        auditService.recordWithIdempotency(
                sessionId,
                userId,
                agentRole,
                SecurityEventType.CHECKPOINT_CREATED,
                autonomy + " autonomy",
                idempotencyKey);
        log.infof(
                "Created checkpoint %d for session=%s, role=%s, autonomy=%s",
                checkpointId, sessionId, agentRole, autonomy);
    }

    // ── markFailed overloads ──────────────────────────────────────────────────

    /** Marks a checkpoint FAILED with a reason only. */
    public void markFailed(Long checkpointId, String reason) {
        markFailed(checkpointId, reason, null, null, null, "unknown");
    }

    /** Marks a checkpoint FAILED by entity reference (reason only). */
    public void markFailed(AgentCheckpoint checkpoint, String reason) {
        markFailed(checkpoint.getId(), reason, null, null, null, "unknown");
    }

    /** Marks a checkpoint FAILED with full diagnostic context. */
    public void markFailed(
            AgentCheckpoint checkpoint, String reason, String errorCode, String traceId, String rootCauseClass) {
        markFailed(checkpoint.getId(), reason, errorCode, traceId, rootCauseClass, "unknown");
    }

    /**
     * Core markFailed implementation - delegates storage, then emits audit.
     *
     * <p>§5.18: The SQLite adapter's UPDATE WHERE clause prevents overwriting
     * COMPLETED / ARCHIVED / COMPROMISED checkpoints. This method does not need
     * to re-check those statuses itself.
     */
    public void markFailed(
            Long checkpointId, String reason, String errorCode, String traceId, String rootCauseClass, String userId) {
        storeAdapter.markFailed(checkpointId, reason, errorCode, traceId, rootCauseClass);
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            String idempotencyKey =
                    "CP_FAILED:" + checkpointId + ":" + dto.createdAt().toEpochMilli();
            String details = String.format("reason=%s errorCode=%s traceId=%s", reason, errorCode, traceId);
            auditService.recordWithIdempotency(
                    dto.sessionId(),
                    userId,
                    dto.agentRole(),
                    SecurityEventType.CHECKPOINT_FAILED,
                    details,
                    idempotencyKey);
            log.warnf("Checkpoint %s/%s marked FAILED: %s", dto.sessionId(), dto.agentRole(), reason);
        });
    }

    // ── markOrphaned ─────────────────────────────────────────────────────────

    /**
     * Marks a checkpoint ORPHANED (scheduler abandoned or process crash) and emits audit.
     */
    public void markOrphaned(Long checkpointId, String reason, String userId) {
        storeAdapter.markOrphaned(checkpointId, reason);
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            String idempotencyKey =
                    "CP_ORPHANED:" + checkpointId + ":" + dto.createdAt().toEpochMilli();
            auditService.recordWithIdempotency(
                    dto.sessionId(),
                    userId,
                    dto.agentRole(),
                    SecurityEventType.CHECKPOINT_ORPHANED,
                    reason,
                    idempotencyKey);
            log.warnf("Checkpoint %s/%s marked ORPHANED: %s", dto.sessionId(), dto.agentRole(), reason);
        });
    }

    // ── markCompleted ─────────────────────────────────────────────────────────

    /**
     * Marks a checkpoint COMPLETED, emits audit, and archives it to prevent reuse.
     */
    public void markCompleted(Long checkpointId, String userId) {
        storeAdapter.markCompleted(checkpointId);
        storeAdapter.findById(checkpointId).ifPresent(dto -> {
            String idempotencyKey =
                    "CP_COMPLETED:" + checkpointId + ":" + dto.createdAt().toEpochMilli();
            auditService.recordWithIdempotency(
                    dto.sessionId(),
                    userId,
                    dto.agentRole(),
                    SecurityEventType.CHECKPOINT_COMPLETED,
                    null,
                    idempotencyKey);
            log.infof("Checkpoint %d marked COMPLETED for session %s", checkpointId, dto.sessionId());

            storeAdapter.archive(checkpointId);
        });
    }

    // ── Audit-only emits (used by saveAndFinalize) ────────────────────────────

    /**
     * Emits an audit event for a checkpoint that was already persisted with a final
     * status by {@code saveCheckpointWithSteps}. Does NOT call any storage method.
     *
     * <p>Separates audit emission from the DB write so {@link StatefulAgentStoreImpl#saveAndFinalize}
     * can record the COMPLETED/FAILED event without triggering a second storage write.
     *
     * @param sessionId   the session ID
     * @param agentRole   the agent role
     * @param userId      already-resolved user ID from the caller
     * @param finalStatus the terminal status (COMPLETED or FAILED)
     * @param checkpointId the persisted checkpoint ID (for the idempotency key)
     * @param createdAt   the creation time of the checkpoint
     */
    public void emitFinalStatus(
            String sessionId,
            String agentRole,
            String userId,
            CheckpointStatus finalStatus,
            Long checkpointId,
            Instant createdAt) {
        SecurityEventType eventType = finalStatus == CheckpointStatus.COMPLETED
                ? SecurityEventType.CHECKPOINT_COMPLETED
                : SecurityEventType.CHECKPOINT_FAILED;
        String idempotencyKey = (finalStatus == CheckpointStatus.COMPLETED ? "CP_COMPLETED:" : "CP_FAILED:")
                + checkpointId + ":" + createdAt.toEpochMilli();
        auditService.recordWithIdempotency(sessionId, userId, agentRole, eventType, null, idempotencyKey);
        if (finalStatus == CheckpointStatus.COMPLETED) {
            log.infof("Checkpoint %d finalized as COMPLETED for session %s", checkpointId, sessionId);
        } else {
            log.warnf("Checkpoint %d finalized as %s for session %s", checkpointId, finalStatus, sessionId);
        }
    }
}
