package com.arcclaw.core.domain.model;

import java.time.Instant;

import com.arcclaw.core.state.CronStatus;

public interface AgentCron {

    String getId();
    void setId(String id);

    String getSessionId();
    void setSessionId(String sessionId);

    String getUserId();
    void setUserId(String userId);

    String getChatId();
    void setChatId(String chatId);

    String getChannelType();
    void setChannelType(String channelType);

    String getChannelPlatformId();
    void setChannelPlatformId(String channelPlatformId);

    String getCronExpression();
    void setCronExpression(String cronExpression);

    String getTimezone();
    void setTimezone(String timezone);

    boolean isRunOnce();
    void setRunOnce(boolean runOnce);

    String getGoal();
    void setGoal(String goal);

    Instant getNextFireTime();
    void setNextFireTime(Instant nextFireTime);

    Instant getLastFireTime();
    void setLastFireTime(Instant lastFireTime);

    CronStatus getStatus();
    void setStatus(CronStatus status);

    String getExecutionRole();
    void setExecutionRole(String executionRole);

    String getErrorReason();
    void setErrorReason(String errorReason);

    String getLastDeliveryId();
    void setLastDeliveryId(String lastDeliveryId);

    Instant getCreatedAt();
    void setCreatedAt(Instant createdAt);

    Instant getUpdatedAt();
    void setUpdatedAt(Instant updatedAt);

    String toDisplayString();
}
