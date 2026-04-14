package com.arcclaw.core.state;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.security.AutonomyLevel;
import com.fasterxml.jackson.annotation.JsonIgnore;

 /**
  * JPA entity for persisting agent reasoning loop state.
 * <p>
 * Each checkpoint captures the full serialized reasoning history
 * ({@code stateJson}) for a given session and agent role, enabling
 * crash recovery and audit trails.
 * <p>
 * <strong>Design note:</strong> Uses plain JPA {@code @Entity} (not
 * {@code PanacheEntity}) because the entity lives in {@code arcclaw-core}
 * while the Quarkus build plugin runs in {@code arcclaw-app}. Panache
 * bytecode enhancement doesn't cross module boundaries. The repository
 * pattern ({@link AgentCheckpointRepository}) provides the Panache API.
 * <p>
 * <strong>SQLite note:</strong> Uses {@code TEXT} column (not {@code @Lob})
 * for better SQLite compatibility.
 */
@Entity
@Table(name = "agent_checkpoint", uniqueConstraints = @UniqueConstraint(name = "uq_checkpoint_session_role", columnNames = {
        "sessionId",
        "agentRole" }))
public class AgentCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkpoint_id", columnDefinition = "INTEGER")
    private Long id;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    /** The workspace session ID this checkpoint belongs to. */
    @Column(nullable = false, length = 128)
    private String sessionId;

    /** The agent role (e.g., "coordinator", "debugger", "researcher", "coder"). */
    @Column(nullable = false, length = 64)
    private String agentRole;

    /** Current lifecycle status of the checkpoint. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckpointStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", length = 20)
    @Nullable
    private AutonomyLevel autonomyLevel;

    /**
     * Jackson-serialized {@code List<ReasoningStep>}.
     * <p>
     * Stored as standard TEXT — no {@code @Lob} for SQLite compatibility.
     * @deprecated Replaced by {@link ReasoningStepEntity} in Phase 55.3.
     */
    @Deprecated
    @Column(columnDefinition = "TEXT")
    @Nullable
    private String stateJson;

    /** Channel platform ID (e.g. Telegram chat ID) — used for timeout notifications. */
    @Column(name = "channel_platform_id", length = 128)
    @Nullable
    private String channelPlatformId;

    /** Channel type (e.g. TELEGRAM, REST_API) — used for timeout notifications. */
    @Column(name = "channel_type", length = 32)
    @Nullable
    private String channelType;

    /**
     * The original user goal that triggered the agent loop which produced this
     * checkpoint. Captured at {@code AWAITING_APPROVAL} transition time.
     * <p>
     * Used on resume so {@code RagContextEnricher} embeds the real user intent
     * instead of the synthetic {@code AgentSentinels.GOAL_RESUME_APPROVED} string,
     * which would produce semantically meaningless RAG query vectors.
     * <p>
     * Truncated to 2048 chars — goals longer than this are rare and SQLite TEXT
     * has no hard limit, but the column bound prevents runaway input.
     */
    @Column(name = "original_goal", length = 2048)
    @Nullable
    private String originalGoal;

    /** Timestamp when a failure occurred (null if not failed). */
    @Nullable
    private Instant failedAt;

    /** Timestamp when the checkpoint completed successfully (null if not completed). */
    @Nullable
    private Instant completedAt;

    /** Human-readable failure reason (null if not failed). */
    @Column(length = 1024)
    @Nullable
    private String failReason;

    @Column(name = "pending_tool_name")
    @Nullable
    private String pendingToolName;

    @Column(name = "batch_tool_decisions", columnDefinition = "TEXT")
    @Nullable
    private String batchToolDecisions; // JSON: {"github":"PENDING","manage_schedule":"PENDING"}

    @Column(name = "pending_tool_parameters", columnDefinition = "TEXT")
    @JsonIgnore // Privacy Fix Phase 41: ensure sensitive tool parameters never leak to telemetry/JSON APIs
    @Nullable
    private String pendingToolParametersJson;

    /**
     * JSON map of toolName → serialized ToolResponse for tools that
     * were pre-executed before the HITL pause. §7.30
     */
    @Column(name = "pre_executed_results", columnDefinition = "TEXT")
    @Nullable
    private String preExecutedResults;

    @Column(name = "max_iterations")
    @Nullable
    private Integer maxIterations;

    @Column(name = "iterations_used")
    private int iterationsUsed = 0;

    /** Estimated duration (ms) to wait before retrying a rate-limited call. */
    @Column(name = "retry_after_ms")
    @Nullable
    private Integer retryAfterMs;

    /** System constraints to be injected into the prompt (separate from original_goal). */
    @Column(name = "system_note", columnDefinition = "TEXT")
    @Nullable
    private String systemNote;

    /** Categorized error code for diagnostics (e.g. AUTH_FAILED, TOOL_TIMEOUT). */
    @Column(name = "error_code", length = 64)
    @Nullable
    private String errorCode;

    /** Correlation ID for tracking failures across logs (OTel traceId). */
    @Column(name = "trace_id", length = 128)
    @Nullable
    private String traceId;

    /** Simple name of the Java exception causing the failure. */
    @Column(name = "root_cause_class", length = 128)
    @Nullable
    private String rootCauseClass;

    /** When this checkpoint was first created. */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** When this checkpoint was last updated. */
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public String getChannelPlatformId() { return channelPlatformId; }
    public void setChannelPlatformId(String channelPlatformId) { this.channelPlatformId = channelPlatformId; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    /**
     * Returns the original user goal captured when this checkpoint was paused
     * for human approval. Use this as the goal for {@code AgentStateMachineRunner}
     * on resume — never use {@link com.arcclaw.core.agent.AgentSentinels#GOAL_RESUME_APPROVED}
     * as the RAG query string.
     */
    public String getOriginalGoal() {
        return originalGoal;
    }

    /**
     * Sets the original user goal. Call this exactly once — inside
     * {@code AgentStepProcessor.persistApprovalCheckpoint()} when the
     * checkpoint transitions to {@code AWAITING_APPROVAL}.
     * Never overwrite an already-set non-blank value.
     */
    public void setOriginalGoal(String originalGoal) {
        this.originalGoal = (originalGoal != null && originalGoal.length() > 2048)
            ? originalGoal.substring(0, 2048)
            : originalGoal;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getPendingToolName() {
        return pendingToolName;
    }

    public void setPendingToolName(String pendingToolName) {
        this.pendingToolName = pendingToolName;
    }

    public String getBatchToolDecisions() {
        return batchToolDecisions;
    }

    public void setBatchToolDecisions(String batchToolDecisions) {
        this.batchToolDecisions = batchToolDecisions;
    }

    /**
     * Returns true if this is a parallel-batch HITL checkpoint with
     * per-tool decision state. §7.20
     */
    public boolean hasBatchToolDecisions() {
        return batchToolDecisions != null && !batchToolDecisions.isBlank();
    }

    public String getPendingToolParametersJson() {
        return pendingToolParametersJson;
    }

    public void setPendingToolParametersJson(String pendingToolParametersJson) {
        this.pendingToolParametersJson = pendingToolParametersJson;
    }

    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }

    public int getIterationsUsed() { return iterationsUsed; }
    public void setIterationsUsed(int iterationsUsed) { this.iterationsUsed = iterationsUsed; }

    public Integer getRetryAfterMs() { return retryAfterMs; }
    public void setRetryAfterMs(Integer retryAfterMs) { this.retryAfterMs = retryAfterMs; }

    public String getSystemNote() { return systemNote; }
    public void setSystemNote(String systemNote) { this.systemNote = systemNote; }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getPreExecutedResults() { return preExecutedResults; }
    public void setPreExecutedResults(String v) { this.preExecutedResults = v; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getRootCauseClass() { return rootCauseClass; }
    public void setRootCauseClass(String rootCauseClass) { this.rootCauseClass = rootCauseClass; }

    public boolean hasPreExecutedResults() {
        return preExecutedResults != null && !preExecutedResults.isBlank();
    }

    public CheckpointStatus getStatus() {
        return status;
    }

    public void setStatus(CheckpointStatus status) {
        this.status = status;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    /**
     * Returns true if this checkpoint represents a paused parallel batch.
     * See Architecture_Invariants.md §7.4.
     */
    public boolean isParallelBatch() {
        return pendingToolName != null
            && pendingToolName.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX);
    }

    /**
     * Returns the individual tool names for a parallel batch checkpoint.
     * Returns empty list for single-tool checkpoints.
     */
    public List<String> parallelToolNames() {
        if (!isParallelBatch()) return Collections.emptyList();
        String composite = pendingToolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length());
        return Arrays.asList(composite.split(","));
    }
}
