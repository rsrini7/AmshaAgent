package com.arcclaw.core.agent.loop;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;

/**
 * Immutable context for an agent loop execution.
 * Encapsulates all inputs required to start or resume a loop.
 *
 * channelType and channelPlatformId are nullable — only present when
 * the loop is driven by an inbound channel message (Telegram, WebSocket, etc.).
 * They are stamped onto the checkpoint inside ApprovalCheckpointManager when
 * the agent pauses for human approval, replacing the post-persist load/save
 * cycle that previously lived in AgentLoop.tryStampChannelInfo().
 */
public final class AgentExecutionContext {
    private final String sessionId;
    private final String userId;
    private final String chatId;
    private final String agentRole;
    private final String goal;
    private final AutonomyLevel autonomyLevel;
    private final int maxIterations;
    private final String channelType;
    private final String channelPlatformId;
    private final String systemNote;
    private final Map<String, Object> data;

    // Hot-path cache (§7.22 / F22)
    private CheckpointStatus lastCheckpointStatus;
    private boolean statusDirty = true;

    public AgentExecutionContext(
            String sessionId,
            String userId,
            String chatId,
            String agentRole,
            String goal,
            AutonomyLevel autonomyLevel,
            int maxIterations,
            String channelType,
            String channelPlatformId,
            String systemNote,
            Map<String, Object> data
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.userId = userId;
        this.chatId = chatId;
        this.agentRole = Objects.requireNonNull(agentRole, "agentRole must not be null");
        this.goal = Objects.requireNonNull(goal, "goal must not be null");
        this.autonomyLevel = Objects.requireNonNull(autonomyLevel, "autonomyLevel must not be null");
        if (maxIterations <= 0)
            throw new IllegalArgumentException("maxIterations must be > 0, got: " + maxIterations);
        this.maxIterations = maxIterations;
        this.channelType = channelType;
        this.channelPlatformId = channelPlatformId;
        this.systemNote = systemNote;
        this.data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
    }

    public String sessionId() { return sessionId; }
    public String userId() { return userId; }
    public String chatId() { return chatId; }
    public String agentRole() { return agentRole; }
    public String goal() { return goal; }
    public String systemNote() { return systemNote != null ? systemNote : ""; }
    public AutonomyLevel autonomyLevel() { return autonomyLevel; }
    public int maxIterations() { return maxIterations; }
    public String channelType() { return channelType; }
    public String channelPlatformId() { return channelPlatformId; }
    public Map<String, Object> data() { return data; }

    public CheckpointStatus lastCheckpointStatus() { return lastCheckpointStatus; }
    public void setLastCheckpointStatus(CheckpointStatus status) {
        this.lastCheckpointStatus = status;
        this.statusDirty = false;
    }

    public boolean isStatusDirty() { return statusDirty; }
    public void markStatusDirty() { this.statusDirty = true; }

    /** Convenience: true when full channel stamping info is available. */
    public boolean hasChannelInfo() {
        return channelType != null && !channelType.isBlank()
            && channelPlatformId != null && !channelPlatformId.isBlank();
    }

    public static AgentExecutionContext from(
            RunAgentRequest req,
            String sessionId,
            String userId,
            String chatId,
            AutonomyLevel autonomyLevel
    ) {
        int max = req.maxIterations() != null ? req.maxIterations() : 5;
        return new AgentExecutionContext(
                sessionId,
                userId,
                chatId,
                req.agentRole(),
                req.goal(),
                autonomyLevel,
                max,
                null,
                null,
                null,
                null
        );
    }
}
