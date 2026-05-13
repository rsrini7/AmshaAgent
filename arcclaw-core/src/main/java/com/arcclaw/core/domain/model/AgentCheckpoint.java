package com.arcclaw.core.domain.model;

import java.time.Instant;
import java.util.List;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;

public interface AgentCheckpoint {

    Long getId();
    void setId(Long id);

    Integer getVersion();
    void setVersion(Integer version);

    String getSessionId();
    void setSessionId(String sessionId);

    String getAgentRole();
    void setAgentRole(String agentRole);

    CheckpointStatus getStatus();
    void setStatus(CheckpointStatus status);

    AutonomyLevel getAutonomyLevel();
    void setAutonomyLevel(AutonomyLevel autonomyLevel);

    String getStateJson();
    void setStateJson(String stateJson);

    String getChannelPlatformId();
    void setChannelPlatformId(String channelPlatformId);

    String getChannelType();
    void setChannelType(String channelType);

    String getOriginalGoal();
    void setOriginalGoal(String originalGoal);

    Instant getFailedAt();
    void setFailedAt(Instant failedAt);

    Instant getCompletedAt();
    void setCompletedAt(Instant completedAt);

    String getFailReason();
    void setFailReason(String failReason);

    String getPendingToolName();
    void setPendingToolName(String pendingToolName);

    String getPendingToolRole();
    void setPendingToolRole(String pendingToolRole);

    String getBatchToolDecisions();
    void setBatchToolDecisions(String batchToolDecisions);

    String getPendingToolParametersJson();
    void setPendingToolParametersJson(String pendingToolParametersJson);

    String getPreExecutedResults();
    void setPreExecutedResults(String preExecutedResults);

    Integer getMaxIterations();
    void setMaxIterations(Integer maxIterations);

    int getIterationsUsed();
    void setIterationsUsed(int iterationsUsed);

    Integer getRetryAfterMs();
    void setRetryAfterMs(Integer retryAfterMs);

    String getSystemNote();
    void setSystemNote(String systemNote);

    String getContextDataJson();
    void setContextDataJson(String contextDataJson);

    String getErrorCode();
    void setErrorCode(String errorCode);

    String getTraceId();
    void setTraceId(String traceId);

    String getRootCauseClass();
    void setRootCauseClass(String rootCauseClass);

    Instant getCreatedAt();
    void setCreatedAt(Instant createdAt);

    Instant getUpdatedAt();
    void setUpdatedAt(Instant updatedAt);

    boolean hasBatchToolDecisions();
    boolean isParallelBatch();
    List<String> parallelToolNames();
    boolean hasBrowserTool();
    boolean hasPreExecutedResults();
}
