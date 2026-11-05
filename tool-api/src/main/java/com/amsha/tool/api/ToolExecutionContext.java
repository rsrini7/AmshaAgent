package com.amsha.tool.api;

import java.util.Objects;
import java.util.UUID;

import com.amsha.security.api.AutonomyLevel;

/** Contextual information passed to {@link AgentTool#execute(ToolExecutionContext, java.util.Map)}. */
public record ToolExecutionContext(
        String sessionId,
        String userId,
        String chatId,
        String agentRole,
        AutonomyLevel autonomyLevel,
        String traceId,
        String channelType,
        String channelPlatformId,
        String rawUserGoal,
        String suggestedCron,
        String suggestedNextFireTime,
        String suggestedCronSource,
        String skillHome,
        boolean browserPreview,
        String operationId,
        String workspaceId,
        String attemptId,
        int attemptNumber,
        long fencingToken) {

    public ToolExecutionContext {
        if (operationId != null && !isCanonicalUuid(operationId)) {
            throw new IllegalArgumentException("operationId must be a canonical lowercase UUID");
        }
        if (attemptId == null) {
            if (attemptNumber != 0 || fencingToken != 0) {
                throw new IllegalArgumentException("attempt identity fields require attemptId");
            }
        } else {
            if (!isCanonicalUuid(attemptId)) {
                throw new IllegalArgumentException("attemptId must be a canonical lowercase UUID");
            }
            if (workspaceId == null || workspaceId.isBlank() || operationId == null) {
                throw new IllegalArgumentException("admitted attempt requires workspaceId and operationId");
            }
            if (attemptNumber <= 0 || fencingToken < 0) {
                throw new IllegalArgumentException(
                        "admitted attempt requires positive attemptNumber and non-negative fencingToken");
            }
        }
    }

    /** Compatibility constructor for callers that supplied the operation ID. */
    public ToolExecutionContext(
            String sessionId,
            String userId,
            String chatId,
            String agentRole,
            AutonomyLevel autonomyLevel,
            String traceId,
            String channelType,
            String channelPlatformId,
            String rawUserGoal,
            String suggestedCron,
            String suggestedNextFireTime,
            String suggestedCronSource,
            String skillHome,
            boolean browserPreview,
            String operationId) {
        this(
                sessionId,
                userId,
                chatId,
                agentRole,
                autonomyLevel,
                traceId,
                channelType,
                channelPlatformId,
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource,
                skillHome,
                browserPreview,
                operationId,
                null,
                null,
                0,
                0);
    }

    /** Compatibility constructor for callers that supplied the browser preview flag. */
    public ToolExecutionContext(
            String sessionId,
            String userId,
            String chatId,
            String agentRole,
            AutonomyLevel autonomyLevel,
            String traceId,
            String channelType,
            String channelPlatformId,
            String rawUserGoal,
            String suggestedCron,
            String suggestedNextFireTime,
            String suggestedCronSource,
            String skillHome,
            boolean browserPreview) {
        this(
                sessionId,
                userId,
                chatId,
                agentRole,
                autonomyLevel,
                traceId,
                channelType,
                channelPlatformId,
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource,
                skillHome,
                browserPreview,
                null);
    }

    public ToolExecutionContext(
            String sessionId,
            String userId,
            String chatId,
            String agentRole,
            AutonomyLevel autonomyLevel,
            String traceId,
            String channelType,
            String channelPlatformId,
            String rawUserGoal,
            String suggestedCron,
            String suggestedNextFireTime,
            String suggestedCronSource) {
        this(
                sessionId,
                userId,
                chatId,
                agentRole,
                autonomyLevel,
                traceId,
                channelType,
                channelPlatformId,
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource,
                null,
                false,
                null);
    }

    /** Compatibility constructor for callers that already supplied skillHome. */
    public ToolExecutionContext(
            String sessionId,
            String userId,
            String chatId,
            String agentRole,
            AutonomyLevel autonomyLevel,
            String traceId,
            String channelType,
            String channelPlatformId,
            String rawUserGoal,
            String suggestedCron,
            String suggestedNextFireTime,
            String suggestedCronSource,
            String skillHome) {
        this(
                sessionId,
                userId,
                chatId,
                agentRole,
                autonomyLevel,
                traceId,
                channelType,
                channelPlatformId,
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource,
                skillHome,
                false,
                null);
    }

    /** Returns this context with the store-allocated attempt identity attached. */
    public ToolExecutionContext withAttempt(
            String workspaceId, String allocatedAttemptId, int allocatedAttemptNumber, long allocatedFencingToken) {
        return new ToolExecutionContext(
                sessionId,
                userId,
                chatId,
                agentRole,
                autonomyLevel,
                traceId,
                channelType,
                channelPlatformId,
                rawUserGoal,
                suggestedCron,
                suggestedNextFireTime,
                suggestedCronSource,
                skillHome,
                browserPreview,
                operationId,
                Objects.requireNonNull(workspaceId, "workspaceId must not be null"),
                Objects.requireNonNull(allocatedAttemptId, "allocatedAttemptId must not be null"),
                allocatedAttemptNumber,
                allocatedFencingToken);
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
