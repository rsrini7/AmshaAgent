package com.amsha.core.domain.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.annotation.JsonIgnore;

public final class CheckpointRecord implements AgentCheckpoint {

    private Long id;
    private Integer version;
    private String sessionId;
    private String agentRole;
    private CheckpointStatus status;
    private AutonomyLevel autonomyLevel;
    private String stateJson;
    private String channelPlatformId;
    private String channelType;
    private String originalGoal;
    private Instant failedAt;
    private Instant completedAt;
    private String failReason;
    private String pendingToolName;
    private String pendingToolRole;
    private String batchToolDecisions;

    @JsonIgnore
    private String pendingToolParametersJson;

    private String preExecutedResults;
    private Integer maxIterations;
    private int iterationsUsed;
    private Integer retryAfterMs;
    private String systemNote;
    private String contextDataJson;
    private String errorCode;
    private String traceId;
    private String rootCauseClass;
    private Long parentCheckpointId;
    private int depth;
    private Instant createdAt;
    private Instant updatedAt;

    /** Required no-arg constructor for JDBI RowMapper / Jackson deserialization. */
    public static CheckpointRecord fromDto(CheckpointDto dto) {
        CheckpointRecord r = new CheckpointRecord();
        r.id = dto.id();
        r.sessionId = dto.sessionId();
        r.agentRole = dto.agentRole();
        r.status = dto.status() != null ? CheckpointStatus.valueOf(dto.status()) : null;
        r.autonomyLevel = dto.autonomyLevel() != null ? AutonomyLevel.valueOf(dto.autonomyLevel()) : null;
        r.stateJson = dto.stateJson();
        r.channelPlatformId = dto.channelPlatformId();
        r.channelType = dto.channelType();
        r.originalGoal = dto.originalGoal();
        r.failedAt = dto.failedAt();
        r.completedAt = dto.completedAt();
        r.failReason = dto.failReason();
        r.pendingToolName = dto.pendingToolName();
        r.batchToolDecisions = dto.batchToolDecisions();
        r.pendingToolParametersJson = dto.pendingToolParametersJson();
        r.preExecutedResults = dto.preExecutedResults();
        r.maxIterations = dto.maxIterations();
        r.iterationsUsed = dto.iterationsUsed();
        r.retryAfterMs = dto.retryAfterMs();
        r.systemNote = dto.systemNote();
        r.contextDataJson = dto.contextDataJson();
        r.errorCode = dto.errorCode();
        r.traceId = dto.traceId();
        r.rootCauseClass = dto.rootCauseClass();
        r.parentCheckpointId = dto.parentCheckpointId();
        r.depth = dto.depth();
        r.createdAt = dto.createdAt();
        r.updatedAt = dto.updatedAt();
        return r;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public Integer getVersion() {
        return version;
    }

    @Override
    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String getAgentRole() {
        return agentRole;
    }

    @Override
    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    @Override
    public CheckpointStatus getStatus() {
        return status;
    }

    @Override
    public void setStatus(CheckpointStatus status) {
        this.status = status;
    }

    @Override
    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    @Override
    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    @Override
    public String getStateJson() {
        return stateJson;
    }

    @Override
    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    @Override
    public String getChannelPlatformId() {
        return channelPlatformId;
    }

    @Override
    public void setChannelPlatformId(String channelPlatformId) {
        this.channelPlatformId = channelPlatformId;
    }

    @Override
    public String getChannelType() {
        return channelType;
    }

    @Override
    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    @Override
    public String getOriginalGoal() {
        return originalGoal;
    }

    @Override
    public void setOriginalGoal(String originalGoal) {
        this.originalGoal = originalGoal;
    }

    @Override
    public Instant getFailedAt() {
        return failedAt;
    }

    @Override
    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    @Override
    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    @Override
    public String getFailReason() {
        return failReason;
    }

    @Override
    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    @Override
    public String getPendingToolName() {
        return pendingToolName;
    }

    @Override
    public void setPendingToolName(String pendingToolName) {
        this.pendingToolName = pendingToolName;
    }

    @Override
    public String getPendingToolRole() {
        return pendingToolRole;
    }

    @Override
    public void setPendingToolRole(String pendingToolRole) {
        this.pendingToolRole = pendingToolRole;
    }

    @Override
    public String getBatchToolDecisions() {
        return batchToolDecisions;
    }

    @Override
    public void setBatchToolDecisions(String batchToolDecisions) {
        this.batchToolDecisions = batchToolDecisions;
    }

    @Override
    public String getPendingToolParametersJson() {
        return pendingToolParametersJson;
    }

    @Override
    public void setPendingToolParametersJson(String pendingToolParametersJson) {
        this.pendingToolParametersJson = pendingToolParametersJson;
    }

    @Override
    public String getPreExecutedResults() {
        return preExecutedResults;
    }

    @Override
    public void setPreExecutedResults(String preExecutedResults) {
        this.preExecutedResults = preExecutedResults;
    }

    @Override
    public Integer getMaxIterations() {
        return maxIterations;
    }

    @Override
    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    @Override
    public int getIterationsUsed() {
        return iterationsUsed;
    }

    @Override
    public void setIterationsUsed(int iterationsUsed) {
        this.iterationsUsed = iterationsUsed;
    }

    @Override
    public Integer getRetryAfterMs() {
        return retryAfterMs;
    }

    @Override
    public void setRetryAfterMs(Integer retryAfterMs) {
        this.retryAfterMs = retryAfterMs;
    }

    @Override
    public String getSystemNote() {
        return systemNote;
    }

    @Override
    public void setSystemNote(String systemNote) {
        this.systemNote = systemNote;
    }

    @Override
    public String getContextDataJson() {
        return contextDataJson;
    }

    @Override
    public void setContextDataJson(String contextDataJson) {
        this.contextDataJson = contextDataJson;
    }

    @Override
    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public String getTraceId() {
        return traceId;
    }

    @Override
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    @Override
    public String getRootCauseClass() {
        return rootCauseClass;
    }

    @Override
    public void setRootCauseClass(String rootCauseClass) {
        this.rootCauseClass = rootCauseClass;
    }

    @Override
    public Long getParentCheckpointId() {
        return parentCheckpointId;
    }

    @Override
    public void setParentCheckpointId(Long parentCheckpointId) {
        this.parentCheckpointId = parentCheckpointId;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public void setDepth(int depth) {
        this.depth = depth;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean hasBatchToolDecisions() {
        return batchToolDecisions != null && !batchToolDecisions.isBlank();
    }

    @Override
    public boolean isParallelBatch() {
        return pendingToolName != null && pendingToolName.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX);
    }

    @Override
    public List<String> parallelToolNames() {
        if (!isParallelBatch()) return Collections.emptyList();
        String composite = pendingToolName.substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length());
        return Arrays.asList(composite.split(",", -1));
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

    @Override
    public boolean hasPreExecutedResults() {
        return preExecutedResults != null && !preExecutedResults.isBlank();
    }
}
