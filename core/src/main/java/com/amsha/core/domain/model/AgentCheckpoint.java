package com.amsha.core.domain.model;

import java.time.Instant;
import java.util.List;

import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.CheckpointStatus;

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

    Long getParentCheckpointId();

    void setParentCheckpointId(Long parentCheckpointId);

    int getDepth();

    void setDepth(int depth);

    boolean hasBatchToolDecisions();

    boolean isParallelBatch();

    List<String> parallelToolNames();

    boolean hasBrowserTool();

    boolean hasPreExecutedResults();

    // ── Wither methods (fluent mutation) ──────────────────────────────────

    default AgentCheckpoint withId(Long id) {
        setId(id);
        return this;
    }

    default AgentCheckpoint withVersion(Integer v) {
        setVersion(v);
        return this;
    }

    default AgentCheckpoint withSessionId(String s) {
        setSessionId(s);
        return this;
    }

    default AgentCheckpoint withAgentRole(String r) {
        setAgentRole(r);
        return this;
    }

    default AgentCheckpoint withStatus(CheckpointStatus s) {
        setStatus(s);
        return this;
    }

    default AgentCheckpoint withAutonomyLevel(AutonomyLevel a) {
        setAutonomyLevel(a);
        return this;
    }

    default AgentCheckpoint withStateJson(String j) {
        setStateJson(j);
        return this;
    }

    default AgentCheckpoint withChannelPlatformId(String p) {
        setChannelPlatformId(p);
        return this;
    }

    default AgentCheckpoint withChannelType(String t) {
        setChannelType(t);
        return this;
    }

    default AgentCheckpoint withOriginalGoal(String g) {
        setOriginalGoal(g);
        return this;
    }

    default AgentCheckpoint withFailedAt(Instant t) {
        setFailedAt(t);
        return this;
    }

    default AgentCheckpoint withCompletedAt(Instant t) {
        setCompletedAt(t);
        return this;
    }

    default AgentCheckpoint withFailReason(String r) {
        setFailReason(r);
        return this;
    }

    default AgentCheckpoint withPendingToolName(String n) {
        setPendingToolName(n);
        return this;
    }

    default AgentCheckpoint withPendingToolRole(String r) {
        setPendingToolRole(r);
        return this;
    }

    default AgentCheckpoint withBatchToolDecisions(String d) {
        setBatchToolDecisions(d);
        return this;
    }

    default AgentCheckpoint withPendingToolParametersJson(String j) {
        setPendingToolParametersJson(j);
        return this;
    }

    default AgentCheckpoint withPreExecutedResults(String r) {
        setPreExecutedResults(r);
        return this;
    }

    default AgentCheckpoint withMaxIterations(Integer m) {
        setMaxIterations(m);
        return this;
    }

    default AgentCheckpoint withIterationsUsed(int i) {
        setIterationsUsed(i);
        return this;
    }

    default AgentCheckpoint withRetryAfterMs(Integer r) {
        setRetryAfterMs(r);
        return this;
    }

    default AgentCheckpoint withSystemNote(String n) {
        setSystemNote(n);
        return this;
    }

    default AgentCheckpoint withContextDataJson(String j) {
        setContextDataJson(j);
        return this;
    }

    default AgentCheckpoint withErrorCode(String c) {
        setErrorCode(c);
        return this;
    }

    default AgentCheckpoint withTraceId(String t) {
        setTraceId(t);
        return this;
    }

    default AgentCheckpoint withRootCauseClass(String c) {
        setRootCauseClass(c);
        return this;
    }

    default AgentCheckpoint withCreatedAt(Instant t) {
        setCreatedAt(t);
        return this;
    }

    default AgentCheckpoint withUpdatedAt(Instant t) {
        setUpdatedAt(t);
        return this;
    }

    default AgentCheckpoint withParentCheckpointId(Long p) {
        setParentCheckpointId(p);
        return this;
    }

    default AgentCheckpoint withDepth(int d) {
        setDepth(d);
        return this;
    }
}
