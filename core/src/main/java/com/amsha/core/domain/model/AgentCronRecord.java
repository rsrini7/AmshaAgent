package com.amsha.core.domain.model;

import java.time.Instant;
import java.util.Locale;

import com.amsha.core.state.CronStatus;
import com.amsha.core.util.TemporalUtils;
import com.amsha.persistence.api.AgentCronDto;

public final class AgentCronRecord implements AgentCron {

    private String id;
    private String sessionId;
    private String userId;
    private String chatId;
    private String channelType;
    private String channelPlatformId;
    private String cronExpression;
    private String timezone;
    private boolean runOnce;
    private String goal;
    private Instant nextFireTime;
    private Instant lastFireTime;
    private CronStatus status;
    private String executionRole;
    private String errorReason;
    private String lastDeliveryId;
    private Instant createdAt;
    private Instant updatedAt;

    /** Required no-arg constructor for JDBI RowMapper / Jackson deserialization. */
    public static AgentCronRecord fromDto(AgentCronDto dto) {
        AgentCronRecord r = new AgentCronRecord();
        r.id = dto.id();
        r.sessionId = dto.sessionId();
        r.userId = dto.userId();
        r.chatId = dto.chatId();
        r.channelType = dto.channelType();
        r.channelPlatformId = dto.channelPlatformId();
        r.cronExpression = dto.cronExpression();
        r.timezone = dto.timezone();
        r.runOnce = dto.runOnce();
        r.goal = dto.goal();
        r.nextFireTime = dto.nextFireTime();
        r.lastFireTime = dto.lastFireTime();
        r.status = dto.status() != null ? CronStatus.valueOf(dto.status()) : null;
        r.executionRole = dto.executionRole();
        r.errorReason = dto.errorReason();
        r.lastDeliveryId = dto.lastDeliveryId();
        r.createdAt = dto.createdAt();
        r.updatedAt = dto.updatedAt();
        return r;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
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
    public String getUserId() {
        return userId;
    }

    @Override
    public void setUserId(String userId) {
        this.userId = userId;
    }

    @Override
    public String getChatId() {
        return chatId;
    }

    @Override
    public void setChatId(String chatId) {
        this.chatId = chatId;
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
    public String getChannelPlatformId() {
        return channelPlatformId;
    }

    @Override
    public void setChannelPlatformId(String channelPlatformId) {
        this.channelPlatformId = channelPlatformId;
    }

    @Override
    public String getCronExpression() {
        return cronExpression;
    }

    @Override
    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    @Override
    public String getTimezone() {
        return timezone;
    }

    @Override
    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    @Override
    public boolean isRunOnce() {
        return runOnce;
    }

    @Override
    public void setRunOnce(boolean runOnce) {
        this.runOnce = runOnce;
    }

    @Override
    public String getGoal() {
        return goal;
    }

    @Override
    public void setGoal(String goal) {
        this.goal = goal;
    }

    @Override
    public Instant getNextFireTime() {
        return nextFireTime;
    }

    @Override
    public void setNextFireTime(Instant nextFireTime) {
        this.nextFireTime = nextFireTime;
    }

    @Override
    public Instant getLastFireTime() {
        return lastFireTime;
    }

    @Override
    public void setLastFireTime(Instant lastFireTime) {
        this.lastFireTime = lastFireTime;
    }

    @Override
    public CronStatus getStatus() {
        return status;
    }

    @Override
    public void setStatus(CronStatus status) {
        this.status = status;
    }

    @Override
    public String getExecutionRole() {
        return executionRole;
    }

    @Override
    public void setExecutionRole(String executionRole) {
        this.executionRole = executionRole;
    }

    @Override
    public String getErrorReason() {
        return errorReason;
    }

    @Override
    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    @Override
    public String getLastDeliveryId() {
        return lastDeliveryId;
    }

    @Override
    public void setLastDeliveryId(String lastDeliveryId) {
        this.lastDeliveryId = lastDeliveryId;
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
    public String toDisplayString() {
        String nextFire = (status == CronStatus.COMPLETED
                        || status == CronStatus.FAILED
                        || status == CronStatus.SKIPPED
                        || nextFireTime == null)
                ? "N/A"
                : TemporalUtils.formatIsoLex(nextFireTime);

        String displayGoal = "N/A";
        if (goal != null) {
            displayGoal = goal.replaceFirst("^CRON\\s+", "").trim();
            String lower = displayGoal.toLowerCase(Locale.ROOT);
            if (lower.startsWith("output the following message:")) {
                displayGoal = displayGoal
                        .substring("output the following message:".length())
                        .trim();
            } else if (lower.startsWith("output the message:")) {
                displayGoal =
                        displayGoal.substring("output the message:".length()).trim();
            } else if (lower.startsWith("say ")) {
                displayGoal = displayGoal.substring("say ".length()).trim();
            } else if (lower.startsWith("tell ")) {
                displayGoal = displayGoal.substring("tell ".length()).trim();
            } else if (lower.startsWith("send message:")) {
                displayGoal = displayGoal.substring("send message:".length()).trim();
            } else if (lower.startsWith("send text:")) {
                displayGoal = displayGoal.substring("send text:".length()).trim();
            }
        }

        return String.format(
                "**ID:** `%s`\n**Cron:** `%s` (%s)\n**Status:** %s\n**Goal:** %s\n**Next:** %s",
                id, cronExpression, timezone, status, displayGoal, nextFire);
    }
}
