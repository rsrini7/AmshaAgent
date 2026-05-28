package com.arcclaw.persistence.common.entity;

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

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity(name = "AgentCheckpoint")
@Table(
        name = "agent_checkpoint",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_checkpoint_session_role",
                        columnNames = {"session_id", "agent_role"}))
public class AgentCheckpointEntity implements AgentCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkpoint_id")
    private Long id;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "agent_role", nullable = false, length = 64)
    private String agentRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckpointStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_level", length = 20)
    @Nullable
    private AutonomyLevel autonomyLevel;

    @Deprecated
    @Column(name = "state_json", columnDefinition = "TEXT")
    @Nullable
    private String stateJson;

    @Column(name = "channel_platform_id", length = 128)
    @Nullable
    private String channelPlatformId;

    @Column(name = "channel_type", length = 32)
    @Nullable
    private String channelType;

    @Column(name = "original_goal", length = 2048)
    @Nullable
    private String originalGoal;

    @Column(name = "failed_at")
    @Nullable
    private Instant failedAt;

    @Column(name = "completed_at")
    @Nullable
    private Instant completedAt;

    @Column(name = "fail_reason", length = 1024)
    @Nullable
    private String failReason;

    @Column(name = "pending_tool_name")
    @Nullable
    private String pendingToolName;

    @Column(name = "pending_tool_role", length = 64)
    @Nullable
    private String pendingToolRole;

    @Column(name = "batch_tool_decisions", columnDefinition = "TEXT")
    @Nullable
    private String batchToolDecisions;

    @Column(name = "pending_tool_parameters", columnDefinition = "TEXT")
    @JsonIgnore
    @Nullable
    private String pendingToolParametersJson;

    @Column(name = "pre_executed_results", columnDefinition = "TEXT")
    @Nullable
    private String preExecutedResults;

    @Column(name = "max_iterations")
    @Nullable
    private Integer maxIterations;

    @Column(name = "iterations_used")
    private int iterationsUsed = 0;

    @Column(name = "retry_after_ms")
    @Nullable
    private Integer retryAfterMs;

    @Column(name = "system_note", columnDefinition = "TEXT")
    @Nullable
    private String systemNote;

    @Column(name = "context_data", columnDefinition = "TEXT")
    @Nullable
    private String contextDataJson;

    @Column(name = "error_code", length = 64)
    @Nullable
    private String errorCode;

    @Column(name = "trace_id", length = 128)
    @Nullable
    private String traceId;

    @Column(name = "root_cause_class", length = 128)
    @Nullable
    private String rootCauseClass;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "parent_checkpoint_id")
    @Nullable
    private Long parentCheckpointId;

    @Column(name = "depth", nullable = false)
    private int depth = 0;

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

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    @Override
    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    @Override
    public String getChannelPlatformId() {
        return channelPlatformId;
    }

    public void setChannelPlatformId(String channelPlatformId) {
        this.channelPlatformId = channelPlatformId;
    }

    @Override
    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    @Override
    public String getOriginalGoal() {
        return originalGoal;
    }

    public void setOriginalGoal(String originalGoal) {
        this.originalGoal =
                (originalGoal != null && originalGoal.length() > 2048) ? originalGoal.substring(0, 2048) : originalGoal;
    }

    @Override
    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    @Override
    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String getPendingToolName() {
        return pendingToolName;
    }

    public void setPendingToolName(String pendingToolName) {
        this.pendingToolName = pendingToolName;
    }

    @Override
    public String getPendingToolRole() {
        return pendingToolRole;
    }

    public void setPendingToolRole(String pendingToolRole) {
        this.pendingToolRole = pendingToolRole;
    }

    @Override
    public String getBatchToolDecisions() {
        return batchToolDecisions;
    }

    public void setBatchToolDecisions(String batchToolDecisions) {
        this.batchToolDecisions = batchToolDecisions;
    }

    @Override
    public boolean hasBatchToolDecisions() {
        return batchToolDecisions != null && !batchToolDecisions.isBlank();
    }

    @Override
    public String getPendingToolParametersJson() {
        return pendingToolParametersJson;
    }

    public void setPendingToolParametersJson(String pendingToolParametersJson) {
        this.pendingToolParametersJson = pendingToolParametersJson;
    }

    @Override
    public Integer getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    @Override
    public int getIterationsUsed() {
        return iterationsUsed;
    }

    public void setIterationsUsed(int iterationsUsed) {
        this.iterationsUsed = iterationsUsed;
    }

    @Override
    public Integer getRetryAfterMs() {
        return retryAfterMs;
    }

    public void setRetryAfterMs(Integer retryAfterMs) {
        this.retryAfterMs = retryAfterMs;
    }

    @Override
    public String getSystemNote() {
        return systemNote;
    }

    public void setSystemNote(String systemNote) {
        this.systemNote = systemNote;
    }

    @Override
    public String getContextDataJson() {
        return contextDataJson;
    }

    public void setContextDataJson(String contextDataJson) {
        this.contextDataJson = contextDataJson;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String getPreExecutedResults() {
        return preExecutedResults;
    }

    public void setPreExecutedResults(String v) {
        this.preExecutedResults = v;
    }

    @Override
    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    @Override
    public String getRootCauseClass() {
        return rootCauseClass;
    }

    public void setRootCauseClass(String rootCauseClass) {
        this.rootCauseClass = rootCauseClass;
    }

    @Override
    public Long getParentCheckpointId() {
        return parentCheckpointId;
    }

    public void setParentCheckpointId(Long parentCheckpointId) {
        this.parentCheckpointId = parentCheckpointId;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    @Override
    public boolean hasPreExecutedResults() {
        return preExecutedResults != null && !preExecutedResults.isBlank();
    }

    @Override
    public CheckpointStatus getStatus() {
        return status;
    }

    public void setStatus(CheckpointStatus status) {
        this.status = status;
    }

    @Override
    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    @Override
    public boolean isParallelBatch() {
        return pendingToolName != null && pendingToolName.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX);
    }

    @Override
    public List<String> parallelToolNames() {
        if (!isParallelBatch()) return Collections.emptyList();
        String composite = pendingToolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length());
        return Arrays.asList(composite.split(","));
    }

    @Override
    public boolean hasBrowserTool() {
        if (pendingToolName == null) return false;

        if (isParallelBatch()) {
            return parallelToolNames().stream()
                    .map(ToolCapability::fromToolName)
                    .flatMap(Optional::stream)
                    .anyMatch(ToolCapability::isBrowserBacked);
        }

        return ToolCapability.fromToolName(pendingToolName)
                .map(ToolCapability::isBrowserBacked)
                .orElse(false);
    }
}
