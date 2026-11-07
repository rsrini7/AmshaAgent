package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptResolutionDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI DAO for PostgreSQL tool attempt settlement journal, locks, claims, and outbox tables.
 */
@RegisterConstructorMapper(ToolAttemptDto.class)
@RegisterConstructorMapper(ToolActionClaimDto.class)
@RegisterConstructorMapper(DurableActionLockDto.class)
@RegisterConstructorMapper(ToolAttemptAuditOutboxDto.class)
@RegisterConstructorMapper(ToolAttemptResolutionDto.class)
public interface ToolAttemptJdbiDao {

    String ATTEMPT_COLUMNS = """
            workspace_id AS "workspaceId",
            session_id AS "sessionId",
            operation_id AS "operationId",
            attempt_id AS "attemptId",
            attempt_number AS "attemptNumber",
            tool_name AS "toolName",
            action_fingerprint AS "actionFingerprint",
            fingerprint_version AS "fingerprintVersion",
            target_fingerprint AS "targetFingerprint",
            idempotency_mode_snapshot AS "idempotencyModeSnapshot",
            status,
            fencing_token AS "fencingToken",
            execution_kind AS "executionKind",
            execution_handle AS "executionHandle",
            process_start_identity AS "processStartIdentity",
            exit_code AS "exitCode",
            sanitized_observation AS "sanitizedObservation",
            created_at AS "createdAt",
            launch_requested_at AS "launchRequestedAt",
            running_at AS "runningAt",
            settled_at AS "settledAt"
            """;

    String CLAIM_COLUMNS = """
            workspace_id AS "workspaceId",
            session_id AS "sessionId",
            tool_name AS "toolName",
            fingerprint_version AS "fingerprintVersion",
            action_fingerprint AS "actionFingerprint",
            claim_state AS "status",
            operation_id AS "operationId",
            attempt_id AS "attemptId",
            created_at AS "createdAt",
            CASE WHEN claim_state = 'CONSUMED' THEN updated_at ELSE NULL END AS "consumedAt"
            """;

    String LOCK_COLUMNS = """
            lock_id AS "lockId",
            workspace_id AS "workspaceId",
            session_id AS "sessionId",
            origin_operation_id AS "originOperationId",
            origin_attempt_id AS "originAttemptId",
            action_fingerprint AS "actionFingerprint",
            target_fingerprint AS "targetFingerprint",
            scope,
            reason,
            status,
            created_at AS "createdAt",
            cleared_at AS "clearedAt",
            resolution_id AS "resolutionId"
            """;

    String OUTBOX_COLUMNS = """
            event_id AS "eventId",
            idempotency_key AS "idempotencyKey",
            workspace_id AS "workspaceId",
            session_id AS "sessionId",
            attempt_id AS "attemptId",
            resolution_id AS "resolutionId",
            event_kind AS "eventKind",
            safe_metadata_json AS "safeMetadataJson",
            created_at AS "createdAt",
            delivery_attempts AS "deliveryAttempts",
            next_attempt_at AS "nextAttemptAt",
            lease_id AS "leaseId",
            lease_until AS "leaseUntil",
            delivered_at AS "deliveredAt",
            delivered_audit_event_id AS "deliveredAuditEventId"
            """;

    String RESOLUTION_COLUMNS = """
            resolution_id AS "resolutionId",
            lock_id AS "lockId",
            workspace_id AS "workspaceId",
            actor_id AS "actorId",
            resolution_kind AS "resolutionKind",
            resolution_status AS "resolutionStatus",
            origin_attempt_id AS "originAttemptId",
            action_fingerprint AS "actionFingerprint",
            target_fingerprint AS "targetFingerprint",
            evidence_reference_or_digest AS "evidenceReferenceOrDigest",
            authorization_outbox_event_id AS "authorizationOutboxEventId",
            resolved_outbox_event_id AS "resolvedOutboxEventId",
            created_at AS "createdAt",
            consumed_at AS "consumedAt"
            """;

    // ── Attempts ─────────────────────────────────────────────────────────────

    @SqlUpdate("""
            INSERT INTO tool_attempt (
                workspace_id, attempt_id, session_id, operation_id, attempt_number,
                tool_name, action_fingerprint, fingerprint_version, target_fingerprint,
                idempotency_mode_snapshot, status, fencing_token, execution_kind,
                execution_handle, process_start_identity, exit_code, sanitized_observation,
                created_at, launch_requested_at, running_at, settled_at
            ) VALUES (
                :workspaceId, :attemptId, :sessionId, :operationId, :attemptNumber,
                :toolName, :actionFingerprint, :fingerprintVersion, :targetFingerprint,
                :idempotencyModeSnapshot, :status, :fencingToken, :executionKind,
                :executionHandle, :processStartIdentity, :exitCode, :sanitizedObservation,
                :createdAt, :launchRequestedAt, :runningAt, :settledAt
            )
            """)
    void insertAttempt(@BindMethods ToolAttemptDto attempt);

    @SqlUpdate("""
            UPDATE tool_attempt SET
                status = :targetStatus,
                fencing_token = :nextFencingToken,
                execution_handle = COALESCE(:executionHandle, execution_handle),
                process_start_identity = COALESCE(:processStartIdentity, process_start_identity),
                exit_code = COALESCE(:exitCode, exit_code),
                sanitized_observation = COALESCE(:sanitizedObservation, sanitized_observation),
                launch_requested_at = COALESCE(:launchRequestedAt, launch_requested_at),
                running_at = COALESCE(:runningAt, running_at),
                settled_at = COALESCE(:settledAt, settled_at)
            WHERE workspace_id = :workspaceId
              AND attempt_id = :attemptId
              AND status = :expectedStatus
              AND fencing_token = :expectedFencingToken
            """)
    int updateAttemptStatus(
            @Bind("workspaceId") String workspaceId,
            @Bind("attemptId") String attemptId,
            @Bind("expectedStatus") String expectedStatus,
            @Bind("expectedFencingToken") long expectedFencingToken,
            @Bind("targetStatus") String targetStatus,
            @Bind("nextFencingToken") long nextFencingToken,
            @Bind("executionHandle") String executionHandle,
            @Bind("processStartIdentity") String processStartIdentity,
            @Bind("exitCode") Integer exitCode,
            @Bind("sanitizedObservation") String sanitizedObservation,
            @Bind("launchRequestedAt") Instant launchRequestedAt,
            @Bind("runningAt") Instant runningAt,
            @Bind("settledAt") Instant settledAt);

    @SqlQuery("SELECT " + ATTEMPT_COLUMNS
            + " FROM tool_attempt WHERE workspace_id = :workspaceId AND attempt_id = :attemptId")
    Optional<ToolAttemptDto> findAttempt(@Bind("workspaceId") String workspaceId, @Bind("attemptId") String attemptId);

    @SqlQuery(
            "SELECT " + ATTEMPT_COLUMNS
                    + " FROM tool_attempt WHERE workspace_id = :workspaceId AND operation_id = :operationId ORDER BY attempt_number DESC LIMIT 1")
    Optional<ToolAttemptDto> findLatestAttemptByOperation(
            @Bind("workspaceId") String workspaceId, @Bind("operationId") String operationId);

    @SqlQuery(
            "SELECT " + ATTEMPT_COLUMNS
                    + " FROM tool_attempt WHERE workspace_id = :workspaceId AND session_id = :sessionId ORDER BY attempt_number ASC, created_at ASC")
    List<ToolAttemptDto> findAttemptsBySession(
            @Bind("workspaceId") String workspaceId, @Bind("sessionId") String sessionId);

    @SqlQuery(
            "SELECT " + ATTEMPT_COLUMNS
                    + " FROM tool_attempt WHERE workspace_id = :workspaceId AND status IN ('ADMITTED', 'LAUNCH_REQUESTED', 'RUNNING') ORDER BY created_at ASC, attempt_number ASC, attempt_id ASC")
    List<ToolAttemptDto> findActiveAttempts(@Bind("workspaceId") String workspaceId);

    @SqlQuery(
            "SELECT " + ATTEMPT_COLUMNS
                    + " FROM tool_attempt WHERE workspace_id = :workspaceId AND operation_id = :operationId ORDER BY attempt_number ASC")
    List<ToolAttemptDto> findAttemptsByOperation(
            @Bind("workspaceId") String workspaceId, @Bind("operationId") String operationId);

    // ── Claims ───────────────────────────────────────────────────────────────

    @SqlUpdate("""
            INSERT INTO tool_action_claim (
                workspace_id, session_id, tool_name, fingerprint_version, action_fingerprint,
                claim_state, operation_id, attempt_id, created_at, updated_at
            ) VALUES (
                :workspaceId, :sessionId, :toolName, :fingerprintVersion, :actionFingerprint,
                :claimState, :operationId, :attemptId, :createdAt, :updatedAt
            )
            """)
    void insertClaim(
            @Bind("workspaceId") String workspaceId,
            @Bind("sessionId") String sessionId,
            @Bind("toolName") String toolName,
            @Bind("fingerprintVersion") int fingerprintVersion,
            @Bind("actionFingerprint") String actionFingerprint,
            @Bind("claimState") String claimState,
            @Bind("operationId") String operationId,
            @Bind("attemptId") String attemptId,
            @Bind("createdAt") Instant createdAt,
            @Bind("updatedAt") Instant updatedAt);

    @SqlQuery(
            "SELECT " + CLAIM_COLUMNS
                    + " FROM tool_action_claim WHERE workspace_id = :workspaceId AND session_id = :sessionId AND tool_name = :toolName AND fingerprint_version = :fingerprintVersion AND action_fingerprint = :actionFingerprint")
    Optional<ToolActionClaimDto> findClaim(
            @Bind("workspaceId") String workspaceId,
            @Bind("sessionId") String sessionId,
            @Bind("toolName") String toolName,
            @Bind("fingerprintVersion") int fingerprintVersion,
            @Bind("actionFingerprint") String actionFingerprint);

    @SqlUpdate("""
            UPDATE tool_action_claim SET
                claim_state = :claimState,
                updated_at = :updatedAt
            WHERE workspace_id = :workspaceId
              AND session_id = :sessionId
              AND tool_name = :toolName
              AND fingerprint_version = :fingerprintVersion
              AND action_fingerprint = :actionFingerprint
            """)
    int updateClaimState(
            @Bind("workspaceId") String workspaceId,
            @Bind("sessionId") String sessionId,
            @Bind("toolName") String toolName,
            @Bind("fingerprintVersion") int fingerprintVersion,
            @Bind("actionFingerprint") String actionFingerprint,
            @Bind("claimState") String claimState,
            @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("""
            DELETE FROM tool_action_claim
            WHERE workspace_id = :workspaceId
              AND session_id = :sessionId
              AND tool_name = :toolName
              AND fingerprint_version = :fingerprintVersion
              AND action_fingerprint = :actionFingerprint
            """)
    int deleteClaim(
            @Bind("workspaceId") String workspaceId,
            @Bind("sessionId") String sessionId,
            @Bind("toolName") String toolName,
            @Bind("fingerprintVersion") int fingerprintVersion,
            @Bind("actionFingerprint") String actionFingerprint);

    @SqlUpdate("DELETE FROM tool_action_claim WHERE workspace_id = :workspaceId AND session_id = :sessionId")
    int deleteClaimsBySession(@Bind("workspaceId") String workspaceId, @Bind("sessionId") String sessionId);

    // ── Locks ────────────────────────────────────────────────────────────────

    @SqlUpdate("""
            INSERT INTO tool_action_lock (
                lock_id, workspace_id, session_id, origin_operation_id, origin_attempt_id,
                action_fingerprint, target_fingerprint, scope, reason, status,
                created_at, cleared_at, resolution_id
            ) VALUES (
                :lockId, :workspaceId, :sessionId, :originOperationId, :originAttemptId,
                :actionFingerprint, :targetFingerprint, :scope, :reason, :status,
                :createdAt, :clearedAt, :resolutionId
            )
            """)
    void insertLock(@BindMethods DurableActionLockDto lock);

    @SqlQuery(
            "SELECT " + LOCK_COLUMNS + " FROM tool_action_lock WHERE workspace_id = :workspaceId AND lock_id = :lockId")
    Optional<DurableActionLockDto> findLock(@Bind("workspaceId") String workspaceId, @Bind("lockId") String lockId);

    @SqlQuery("SELECT " + LOCK_COLUMNS
            + " FROM tool_action_lock WHERE workspace_id = :workspaceId AND lock_id = :lockId AND status = 'ACTIVE'")
    Optional<DurableActionLockDto> findActiveLock(
            @Bind("workspaceId") String workspaceId, @Bind("lockId") String lockId);

    @SqlQuery("SELECT " + LOCK_COLUMNS + """
            FROM tool_action_lock
            WHERE workspace_id = :workspaceId
              AND status = 'ACTIVE'
              AND (
                  (scope = 'EXACT_REQUEST' AND action_fingerprint = :actionFingerprint)
                  OR (scope = 'TARGET_RESOURCE' AND :targetFingerprint IS NOT NULL AND target_fingerprint = :targetFingerprint)
                  OR (scope = 'SESSION_MUTATIONS' AND session_id = :sessionId)
              )
            ORDER BY created_at ASC
            """)
    List<DurableActionLockDto> findApplicableLocks(
            @Bind("workspaceId") String workspaceId,
            @Bind("sessionId") String sessionId,
            @Bind("actionFingerprint") String actionFingerprint,
            @Bind("targetFingerprint") String targetFingerprint);

    @SqlUpdate("""
            UPDATE tool_action_lock SET
                status = 'CLEARED',
                cleared_at = :clearedAt,
                resolution_id = :resolutionId
            WHERE workspace_id = :workspaceId
              AND lock_id = :lockId
              AND status = 'ACTIVE'
            """)
    int clearLock(
            @Bind("workspaceId") String workspaceId,
            @Bind("lockId") String lockId,
            @Bind("clearedAt") Instant clearedAt,
            @Bind("resolutionId") String resolutionId);

    // ── Outbox ───────────────────────────────────────────────────────────────

    @SqlUpdate("""
            INSERT INTO tool_attempt_outbox (
                event_id, idempotency_key, workspace_id, session_id, attempt_id,
                resolution_id, event_kind, safe_metadata_json, created_at,
                delivery_attempts, next_attempt_at, lease_id, lease_until,
                delivered_at, delivered_audit_event_id
            ) VALUES (
                :eventId, :idempotencyKey, :workspaceId, :sessionId, :attemptId,
                :resolutionId, :eventKind, :safeMetadataJson, :createdAt,
                :deliveryAttempts, :nextAttemptAt, :leaseId, :leaseUntil,
                :deliveredAt, :deliveredAuditEventId
            )
            """)
    void insertOutboxEvent(@BindMethods ToolAttemptAuditOutboxDto event);

    @SqlQuery("SELECT " + OUTBOX_COLUMNS
            + " FROM tool_attempt_outbox WHERE workspace_id = :workspaceId AND event_id = :eventId")
    Optional<ToolAttemptAuditOutboxDto> findOutboxEvent(
            @Bind("workspaceId") String workspaceId, @Bind("eventId") String eventId);

    @SqlQuery("SELECT " + OUTBOX_COLUMNS
            + " FROM tool_attempt_outbox WHERE workspace_id = :workspaceId AND idempotency_key = :key")
    Optional<ToolAttemptAuditOutboxDto> findOutboxEventByIdempotencyKey(
            @Bind("workspaceId") String workspaceId, @Bind("key") String idempotencyKey);

    @SqlQuery("SELECT " + OUTBOX_COLUMNS + """
            FROM tool_attempt_outbox
            WHERE workspace_id = :workspaceId
              AND delivered_at IS NULL
              AND next_attempt_at <= :now
              AND (lease_until IS NULL OR lease_until < :now)
            ORDER BY next_attempt_at ASC, created_at ASC
            LIMIT :limit
            """)
    List<ToolAttemptAuditOutboxDto> findDeliverableOutboxEvents(
            @Bind("workspaceId") String workspaceId, @Bind("now") Instant now, @Bind("limit") int limit);

    @SqlUpdate("""
            UPDATE tool_attempt_outbox SET
                lease_id = :leaseId,
                lease_until = :leaseUntil
            WHERE workspace_id = :workspaceId
              AND event_id IN (<eventIds>)
            """)
    int leaseOutboxEvents(
            @Bind("workspaceId") String workspaceId,
            @Bind("leaseId") String leaseId,
            @Bind("leaseUntil") Instant leaseUntil,
            @BindList("eventIds") List<String> eventIds);

    @SqlUpdate("""
            UPDATE tool_attempt_outbox SET
                delivered_at = :deliveredAt,
                delivered_audit_event_id = :deliveredAuditEventId,
                lease_id = NULL,
                lease_until = NULL
            WHERE workspace_id = :workspaceId
              AND event_id = :eventId
              AND lease_id = :leaseId
            """)
    int acknowledgeOutboxEvent(
            @Bind("workspaceId") String workspaceId,
            @Bind("eventId") String eventId,
            @Bind("leaseId") String leaseId,
            @Bind("deliveredAuditEventId") String deliveredAuditEventId,
            @Bind("deliveredAt") Instant deliveredAt);

    @SqlUpdate("""
            UPDATE tool_attempt_outbox SET
                next_attempt_at = :nextAttemptAt,
                delivery_attempts = :deliveryAttempts,
                lease_id = NULL,
                lease_until = NULL
            WHERE workspace_id = :workspaceId
              AND event_id = :eventId
              AND lease_id = :leaseId
            """)
    int rescheduleOutboxEvent(
            @Bind("workspaceId") String workspaceId,
            @Bind("eventId") String eventId,
            @Bind("leaseId") String leaseId,
            @Bind("nextAttemptAt") Instant nextAttemptAt,
            @Bind("deliveryAttempts") int deliveryAttempts);

    // ── Resolutions ──────────────────────────────────────────────────────────

    @SqlUpdate("""
            INSERT INTO tool_attempt_resolution (
                resolution_id, lock_id, workspace_id, actor_id, resolution_kind,
                resolution_status, origin_attempt_id, action_fingerprint, target_fingerprint,
                evidence_reference_or_digest, authorization_outbox_event_id,
                resolved_outbox_event_id, created_at, consumed_at
            ) VALUES (
                :resolutionId, :lockId, :workspaceId, :actorId, :resolutionKind,
                :resolutionStatus, :originAttemptId, :actionFingerprint, :targetFingerprint,
                :evidenceReferenceOrDigest, :authorizationOutboxEventId,
                :resolvedOutboxEventId, :createdAt, :consumedAt
            )
            """)
    void insertResolution(@BindMethods ToolAttemptResolutionDto resolution);

    @SqlQuery("SELECT " + RESOLUTION_COLUMNS
            + " FROM tool_attempt_resolution WHERE workspace_id = :workspaceId AND resolution_id = :resolutionId")
    Optional<ToolAttemptResolutionDto> findResolution(
            @Bind("workspaceId") String workspaceId, @Bind("resolutionId") String resolutionId);

    @SqlQuery(
            "SELECT " + RESOLUTION_COLUMNS
                    + " FROM tool_attempt_resolution WHERE workspace_id = :workspaceId AND lock_id = :lockId ORDER BY created_at DESC LIMIT 1")
    Optional<ToolAttemptResolutionDto> findResolutionByLockId(
            @Bind("workspaceId") String workspaceId, @Bind("lockId") String lockId);

    @SqlUpdate("""
            UPDATE tool_attempt_resolution SET
                resolution_status = 'APPLIED',
                consumed_at = :consumedAt,
                resolved_outbox_event_id = :resolvedOutboxEventId
            WHERE workspace_id = :workspaceId
              AND resolution_id = :resolutionId
              AND resolution_status = 'PENDING'
            """)
    int applyResolution(
            @Bind("workspaceId") String workspaceId,
            @Bind("resolutionId") String resolutionId,
            @Bind("consumedAt") Instant consumedAt,
            @Bind("resolvedOutboxEventId") String resolvedOutboxEventId);

    // ── Admin & Pruning ──────────────────────────────────────────────────────

    @SqlQuery("SELECT DISTINCT workspace_id FROM tool_attempt")
    Set<String> listWorkspaceIds();

    @SqlUpdate("""
            DELETE FROM tool_attempt_outbox
            WHERE workspace_id = :workspaceId
              AND delivered_at IS NOT NULL
              AND delivered_at < :cutoff
            """)
    int pruneDeliveredOutboxRows(@Bind("workspaceId") String workspaceId, @Bind("cutoff") Instant cutoff);

    @SqlUpdate("""
            DELETE FROM tool_attempt_resolution
            WHERE workspace_id = :workspaceId
              AND resolution_status != 'PENDING'
              AND (consumed_at < :cutoff OR (consumed_at IS NULL AND created_at < :cutoff))
            """)
    int pruneTerminalResolutions(@Bind("workspaceId") String workspaceId, @Bind("cutoff") Instant cutoff);

    @SqlUpdate("""
            DELETE FROM tool_action_lock
            WHERE workspace_id = :workspaceId
              AND status = 'CLEARED'
              AND cleared_at < :cutoff
            """)
    int pruneClearedLocks(@Bind("workspaceId") String workspaceId, @Bind("cutoff") Instant cutoff);

    @SqlUpdate("""
            DELETE FROM tool_attempt
            WHERE workspace_id = :workspaceId
              AND status IN ('SETTLED_SUCCESS', 'SETTLED_ERROR', 'TIMED_OUT', 'CANCELLED_BEFORE_LAUNCH', 'INDETERMINATE')
              AND settled_at < :cutoff
              AND attempt_id NOT IN (
                  SELECT attempt_id FROM tool_action_claim WHERE workspace_id = :workspaceId
              )
              AND attempt_id NOT IN (
                  SELECT origin_attempt_id FROM tool_action_lock WHERE workspace_id = :workspaceId AND status = 'ACTIVE'
              )
              AND attempt_id NOT IN (
                  SELECT origin_attempt_id FROM tool_attempt_resolution WHERE workspace_id = :workspaceId AND resolution_status = 'PENDING'
              )
            """)
    int pruneTerminalAttempts(@Bind("workspaceId") String workspaceId, @Bind("cutoff") Instant cutoff);
}
