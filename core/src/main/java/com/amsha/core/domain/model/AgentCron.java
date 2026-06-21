package com.amsha.core.domain.model;

import java.time.Instant;

import com.amsha.core.state.CronStatus;

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

    // ── Wither methods ────────────────────────────────────────────────────

    default AgentCron withId(String id) {
        setId(id);
        return this;
    }

    default AgentCron withSessionId(String s) {
        setSessionId(s);
        return this;
    }

    default AgentCron withUserId(String u) {
        setUserId(u);
        return this;
    }

    default AgentCron withChatId(String c) {
        setChatId(c);
        return this;
    }

    default AgentCron withChannelType(String t) {
        setChannelType(t);
        return this;
    }

    default AgentCron withChannelPlatformId(String p) {
        setChannelPlatformId(p);
        return this;
    }

    default AgentCron withCronExpression(String e) {
        setCronExpression(e);
        return this;
    }

    default AgentCron withTimezone(String t) {
        setTimezone(t);
        return this;
    }

    default AgentCron withRunOnce(boolean r) {
        setRunOnce(r);
        return this;
    }

    default AgentCron withGoal(String g) {
        setGoal(g);
        return this;
    }

    default AgentCron withNextFireTime(Instant t) {
        setNextFireTime(t);
        return this;
    }

    default AgentCron withLastFireTime(Instant t) {
        setLastFireTime(t);
        return this;
    }

    default AgentCron withStatus(CronStatus s) {
        setStatus(s);
        return this;
    }

    default AgentCron withExecutionRole(String r) {
        setExecutionRole(r);
        return this;
    }

    default AgentCron withErrorReason(String r) {
        setErrorReason(r);
        return this;
    }

    default AgentCron withLastDeliveryId(String d) {
        setLastDeliveryId(d);
        return this;
    }

    default AgentCron withCreatedAt(Instant t) {
        setCreatedAt(t);
        return this;
    }

    default AgentCron withUpdatedAt(Instant t) {
        setUpdatedAt(t);
        return this;
    }
}
