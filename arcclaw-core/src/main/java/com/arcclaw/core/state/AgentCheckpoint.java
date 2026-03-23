package com.arcclaw.core.state;

import java.time.Instant;
import java.time.LocalDateTime;

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
    private AutonomyLevel autonomyLevel;

    /**
     * Jackson-serialized {@code List<ReasoningStep>}.
     * <p>
     * Stored as standard TEXT — no {@code @Lob} for SQLite compatibility.
     * @deprecated Replaced by {@link ReasoningStepEntity} in Phase 55.3.
     */
    @Deprecated
    @Column(columnDefinition = "TEXT")
    private String stateJson;

    /** Channel platform ID (e.g. Telegram chat ID) — used for timeout notifications. */
    @Column(name = "channel_platform_id", length = 128)
    private String channelPlatformId;

    /** Channel type (e.g. TELEGRAM, REST_API) — used for timeout notifications. */
    @Column(name = "channel_type", length = 32)
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
    private String originalGoal;

    /** Timestamp when a failure occurred (null if not failed). */
    private Instant failedAt;

    /** Timestamp when the checkpoint completed successfully (null if not completed). */
    private Instant completedAt;

    /** Human-readable failure reason (null if not failed). */
    @Column(length = 1024)
    private String failReason;

    @Column(name = "pending_tool_name")
    private String pendingToolName;

    @Column(name = "pending_tool_parameters", columnDefinition = "TEXT")
    @JsonIgnore // Privacy Fix Phase 41: ensure sensitive tool parameters never leak to telemetry/JSON APIs
    private String pendingToolParametersJson;

    /** When this checkpoint was first created. */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When this checkpoint was last updated. */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onPersist() {
        var now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
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
        // Guard: never overwrite a previously captured goal — the first
        // real user intent is the one that matters for RAG on resume.
        if (this.originalGoal == null || this.originalGoal.isBlank()) {
            this.originalGoal = originalGoal != null && originalGoal.length() > 2048
                    ? originalGoal.substring(0, 2048)
                    : originalGoal;
        }
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getPendingToolName() {
        return pendingToolName;
    }

    public void setPendingToolName(String pendingToolName) {
        this.pendingToolName = pendingToolName;
    }

    public String getPendingToolParametersJson() {
        return pendingToolParametersJson;
    }

    public void setPendingToolParametersJson(String pendingToolParametersJson) {
        this.pendingToolParametersJson = pendingToolParametersJson;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
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
}
