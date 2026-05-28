package com.arcclaw.persistence.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.state.CronStatus;
import com.arcclaw.core.util.TemporalUtils;

/**
 * Persistent cron job entity backed by the {@code agent_cron} SQLite table.
 * <p>
 * All fields are public (project convention: no getters/setters).
 * Lifecycle: ACTIVE → RUNNING → ACTIVE (recurring) / COMPLETED (one-shot) / FAILED.
 */
@Entity
@Table(name = "agent_cron")
public class AgentCronEntity implements AgentCron {

    @Id
    public String id;

    public String sessionId;

    public String userId;

    public String chatId;

    /** ChannelType enum name. Null = legacy row → TELEGRAM. */
    @Column(nullable = true)
    public String channelType;

    /** Channel-specific recipient ID. Null → fall back to chatId. */
    @Column(nullable = true)
    public String channelPlatformId;

    public String cronExpression;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String timezone = "UTC";

    /**
     * Explicit default value for GraalVM native safety.
     * Note: @ColumnDefault("0") was removed to maintain core-persistence boundary.
     * Schema generation is managed via external tools (Flyway/Liquibase) where the
     * DEFAULT 0 clause must be explicitly defined for the runonce column.
     */
    @Column(name = "runonce", nullable = false)
    public boolean runOnce = false;

    public String goal;

    public Instant nextFireTime;

    public Instant lastFireTime;

    @Convert(converter = CronStatusConverter.class)
    public CronStatus status = CronStatus.ACTIVE;

    @Column(nullable = true)
    public String executionRole;

    public String errorReason;

    /**
     * Idempotency key - set BEFORE Telegram send. Provides observability
     * for at-least-once delivery semantics. If the app crashes before delivery,
     * the job remains ACTIVE and will be retried on next poll.
     */
    public String lastDeliveryId;

    public Instant createdAt;

    public Instant updatedAt;

    /**
     * Canonical display string used by reflexes and tools.
     */
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
            String lower = displayGoal.toLowerCase();
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
}
