package com.arcclaw.persistence.postgres.jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.ReasoningStepDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(CheckpointDto.class)
public interface CheckpointJdbiDao {

    String ALL_COLUMNS = """
            checkpoint_id AS "id",
            session_id AS "sessionId",
            agent_role AS "agentRole",
            status,
            autonomy_level AS "autonomyLevel",
            state_json AS "stateJson",
            channel_platform_id AS "channelPlatformId",
            channel_type AS "channelType",
            original_goal AS "originalGoal",
            failed_at AS "failedAt",
            completed_at AS "completedAt",
            fail_reason AS "failReason",
            pending_tool_name AS "pendingToolName",
            batch_tool_decisions AS "batchToolDecisions",
            pending_tool_parameters AS "pendingToolParametersJson",
            pre_executed_results AS "preExecutedResults",
            max_iterations AS "maxIterations",
            iterations_used AS "iterationsUsed",
            retry_after_ms AS "retryAfterMs",
            system_note AS "systemNote",
            context_data AS "contextDataJson",
            error_code AS "errorCode",
            trace_id AS "traceId",
            root_cause_class AS "rootCauseClass",
            parent_checkpoint_id AS "parentCheckpointId",
            depth,
            created_at AS "createdAt",
            updated_at AS "updatedAt"
            """;

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE checkpoint_id = :id")
    Optional<CheckpointDto> findById(@Bind("id") Long id);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND agent_role = :agentRole AND status <> 'ARCHIVED' ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findBySessionAndRole(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND agent_role = :agentRole ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findAnyBySessionAndRole(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND status NOT IN ('COMPLETED', 'FAILED', 'DENIED', 'ARCHIVED') ORDER BY checkpoint_id ASC")
    List<CheckpointDto> findActiveBySession(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND status IN ('AWAITING_APPROVAL', 'RESUMING', 'IN_PROGRESS') ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findLatestAwaiting(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE status = :status ORDER BY checkpoint_id DESC")
    List<CheckpointDto> findByStatus(@Bind("status") String status);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE status NOT IN ('COMPLETED', 'FAILED', 'DENIED', 'ARCHIVED') ORDER BY updated_at DESC")
    List<CheckpointDto> findAllActive();

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findLatestBySession(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND status <> 'ARCHIVED' ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findLatestNonArchived(@Bind("sessionId") String sessionId);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND status = :status ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findLatestBySessionAndStatus(@Bind("sessionId") String sessionId, @Bind("status") String status);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id LIKE '%' || :segment || '%' AND status IN ('AWAITING_APPROVAL', 'RESUMING', 'IN_PROGRESS') ORDER BY checkpoint_id DESC LIMIT 1")
    Optional<CheckpointDto> findLatestAwaitingBySegment(@Bind("segment") String segment);

    @SqlQuery("SELECT COUNT(*) > 0 FROM agent_checkpoint WHERE channel_platform_id = :chatId AND status = 'AWAITING_APPROVAL' AND session_id NOT LIKE 'cron-%'")
    boolean hasPendingApprovalForChat(@Bind("chatId") String chatId);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE status = 'IN_PROGRESS' AND updated_at < :cutoff")
    List<CheckpointDto> findInProgressZombies(@Bind("cutoff") Instant cutoff);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE status = 'AWAITING_APPROVAL' AND updated_at < :cutoff")
    List<CheckpointDto> findStaleApprovals(@Bind("cutoff") Instant cutoff);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND status = 'AWAITING_APPROVAL' AND updated_at < :cutoff ORDER BY checkpoint_id DESC LIMIT 1")
    List<CheckpointDto> findExpiredCheckpoints(@Bind("sessionId") String sessionId, @Bind("cutoff") Instant cutoff);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM agent_checkpoint WHERE session_id = :sessionId AND status NOT IN ('COMPLETED', 'FAILED', 'DENIED', 'ARCHIVED') LIMIT 1")
    List<CheckpointDto> findActiveBySessionAny(@Bind("sessionId") String sessionId);

    // ── Status Transitions ─────────────────────────────────────────────────

    @SqlUpdate("UPDATE agent_checkpoint SET status = :status, updated_at = :updatedAt WHERE checkpoint_id = :id")
    void transitionStatus(@Bind("id") Long id, @Bind("status") String status, @Bind("updatedAt") Instant updatedAt);

    @SqlUpdate("UPDATE agent_checkpoint SET status = :status, updated_at = :updatedAt WHERE checkpoint_id = :id AND status = :expectedStatus")
    boolean atomicTransition(@Bind("id") Long id, @Bind("status") String status, @Bind("updatedAt") Instant updatedAt, @Bind("expectedStatus") String expectedStatus);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'FAILED', fail_reason = :reason, error_code = :code, trace_id = :trace, root_cause_class = :root, failed_at = :now, updated_at = :now WHERE checkpoint_id = :id")
    void markFailed(@Bind("id") Long id, @Bind("reason") String reason, @Bind("code") String code, @Bind("trace") String trace, @Bind("root") String root, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'COMPLETED', completed_at = :now, updated_at = :now WHERE checkpoint_id = :id")
    void markCompleted(@Bind("id") Long id, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'ORPHANED', fail_reason = :reason, failed_at = :now, updated_at = :now WHERE checkpoint_id = :id")
    void markOrphaned(@Bind("id") Long id, @Bind("reason") String reason, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'ARCHIVED', updated_at = :now WHERE checkpoint_id = :id")
    void archive(@Bind("id") Long id, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'AWAITING_RETRY', retry_after_ms = :retryAfterMs, updated_at = :now WHERE checkpoint_id = :id")
    void markForRetry(@Bind("id") Long id, @Bind("retryAfterMs") Integer retryAfterMs, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'IN_PROGRESS', retry_after_ms = NULL, updated_at = :now WHERE checkpoint_id = :id AND status = 'AWAITING_RETRY'")
    boolean claimForRetry(@Bind("id") Long id, @Bind("now") Instant now);

    // ── Resume Claim ──────────────────────────────────────────────────────

    @SqlUpdate("""
            UPDATE agent_checkpoint SET status = 'RESUMING', updated_at = :now
            WHERE checkpoint_id = (
                SELECT checkpoint_id FROM agent_checkpoint
                WHERE session_id = :sessionId AND agent_role = :agentRole
                ORDER BY checkpoint_id DESC LIMIT 1
            )
            AND (status = 'AWAITING_APPROVAL' OR (status IN ('RESUMING', 'IN_PROGRESS') AND updated_at < :cutoff))
            """)
    boolean claimForResume(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole, @Bind("now") Instant now, @Bind("cutoff") Instant cutoff);

    @SqlUpdate("""
            UPDATE agent_checkpoint SET status = 'AWAITING_APPROVAL', updated_at = :now
            WHERE checkpoint_id = (
                SELECT checkpoint_id FROM agent_checkpoint
                WHERE session_id = :sessionId AND agent_role = :agentRole
                ORDER BY checkpoint_id DESC LIMIT 1
            )
            AND status = 'RESUMING'
            """)
    void releaseResumeClaim(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole, @Bind("now") Instant now);

    // ── Full Row Upsert ────────────────────────────────────────────────────

    @SqlUpdate("""
            INSERT INTO agent_checkpoint (
                version, session_id, agent_role, status, autonomy_level, state_json,
                channel_platform_id, channel_type, original_goal, failed_at, completed_at,
                fail_reason, pending_tool_name, pending_tool_role, batch_tool_decisions,
                pending_tool_parameters, pre_executed_results, max_iterations, iterations_used,
                retry_after_ms, system_note, context_data, error_code, trace_id,
                root_cause_class, parent_checkpoint_id, depth, created_at, updated_at
            ) VALUES (
                0, :sessionId, :agentRole, :status, :autonomyLevel, :stateJson,
                :channelPlatformId, :channelType, :originalGoal, :failedAt, :completedAt,
                :failReason, :pendingToolName, NULL, :batchToolDecisions,
                :pendingToolParametersJson, :preExecutedResults, :maxIterations, :iterationsUsed,
                :retryAfterMs, :systemNote, :contextDataJson, :errorCode, :traceId,
                :rootCauseClass, :parentCheckpointId, :depth, :createdAt, :updatedAt
            )
            ON CONFLICT(session_id, agent_role) DO UPDATE SET
                version = agent_checkpoint.version + 1,
                status = excluded.status,
                autonomy_level = COALESCE(excluded.autonomy_level, agent_checkpoint.autonomy_level),
                state_json = excluded.state_json,
                channel_platform_id = excluded.channel_platform_id,
                channel_type = excluded.channel_type,
                original_goal = COALESCE(excluded.original_goal, agent_checkpoint.original_goal),
                failed_at = excluded.failed_at,
                completed_at = excluded.completed_at,
                fail_reason = excluded.fail_reason,
                pending_tool_name = excluded.pending_tool_name,
                batch_tool_decisions = excluded.batch_tool_decisions,
                pending_tool_parameters = excluded.pending_tool_parameters,
                pre_executed_results = excluded.pre_executed_results,
                max_iterations = excluded.max_iterations,
                iterations_used = excluded.iterations_used,
                retry_after_ms = excluded.retry_after_ms,
                system_note = excluded.system_note,
                context_data = excluded.context_data,
                error_code = excluded.error_code,
                trace_id = excluded.trace_id,
                root_cause_class = excluded.root_cause_class,
                parent_checkpoint_id = excluded.parent_checkpoint_id,
                depth = excluded.depth,
                updated_at = excluded.updated_at
            """)
    void upsertCheckpoint(@BindMethods CheckpointDto dto);

    // ── Targeted Column Updates ───────────────────────────────────────────

    @SqlUpdate("UPDATE agent_checkpoint SET channel_type = :channelType, channel_platform_id = :channelPlatformId, updated_at = :now, version = version + 1 WHERE checkpoint_id = :id")
    void stampChannelInfo(@Bind("id") Long id, @Bind("channelType") String channelType, @Bind("channelPlatformId") String channelPlatformId, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET batch_tool_decisions = :json, updated_at = :now WHERE session_id = :sessionId AND agent_role = :agentRole AND status = 'AWAITING_APPROVAL'")
    boolean atomicUpdateBatchDecision(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole, @Bind("json") String json, @Bind("now") Instant now);

    @SqlUpdate("UPDATE agent_checkpoint SET status = :status, batch_tool_decisions = :json, updated_at = :now WHERE session_id = :sessionId AND agent_role = :agentRole AND status = 'AWAITING_APPROVAL'")
    boolean atomicUpdateBatchDecisionWithStatus(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole, @Bind("status") String status, @Bind("json") String json, @Bind("now") Instant now);

    // ── Bulk Operations ────────────────────────────────────────────────────

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'FAILED', fail_reason = :reason, failed_at = :now, updated_at = :now WHERE session_id = :sessionId AND status NOT IN ('COMPLETED', 'FAILED', 'DENIED', 'ARCHIVED')")
    int markCheckpointsFailed(@Bind("sessionId") String sessionId, @Bind("reason") String reason, @Bind("now") Instant now);

    @SqlUpdate("DELETE FROM agent_checkpoint WHERE session_id = :sessionId AND agent_role = :agentRole")
    void deleteCheckpoint(@Bind("sessionId") String sessionId, @Bind("agentRole") String agentRole);

    @SqlUpdate("DELETE FROM agent_checkpoint WHERE checkpoint_id = :id")
    void deleteById(@Bind("id") Long id);

    @SqlUpdate("UPDATE agent_checkpoint SET status = 'FAILED', fail_reason = :reason, error_code = 'ZOMBIE', failed_at = :now, updated_at = :now WHERE status = 'IN_PROGRESS' AND updated_at < :cutoff")
    int recoverInProgressZombies(@Bind("cutoff") Instant cutoff, @Bind("reason") String reason, @Bind("now") Instant now);

    @SqlUpdate("DELETE FROM reasoning_step WHERE checkpoint_id IN (SELECT checkpoint_id FROM agent_checkpoint WHERE updated_at < :cutoff)")
    int deleteReasoningStepsForPurge(@Bind("cutoff") Instant cutoff);

    @SqlUpdate("DELETE FROM agent_checkpoint WHERE updated_at < :cutoff")
    int deleteCheckpointsForPurge(@Bind("cutoff") Instant cutoff);

    // ── Reasoning Steps ────────────────────────────────────────────────────

    @SqlUpdate("DELETE FROM reasoning_step WHERE checkpoint_id = :checkpointId")
    void deleteSteps(@Bind("checkpointId") Long checkpointId);

    @SqlQuery("SELECT thoughtJson AS \"thoughtJson\", action, actionInputJson AS \"actionInputJson\", observation, input_tokens AS \"inputTokens\", output_tokens AS \"outputTokens\", success, error_code AS \"errorCode\", trace_id AS \"traceId\", root_cause_class AS \"rootCauseClass\" FROM reasoning_step WHERE checkpoint_id = :checkpointId ORDER BY sequence_num ASC")
    @RegisterConstructorMapper(ReasoningStepDto.class)
    List<ReasoningStepDto> loadSteps(@Bind("checkpointId") Long checkpointId);
}
