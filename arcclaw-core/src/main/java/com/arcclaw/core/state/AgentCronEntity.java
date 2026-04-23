package com.arcclaw.core.state;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.arcclaw.core.util.TemporalUtils;

/**
 * Persistent cron job entity backed by the {@code agent_cron} SQLite table.
 * <p>
 * All fields are public (project convention: no getters/setters).
 * Lifecycle: ACTIVE → RUNNING → ACTIVE (recurring) / COMPLETED (one-shot) / FAILED.
 */
@Entity
@Table(name = "agent_cron")
public class AgentCronEntity {

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
     * Idempotency key — set BEFORE Telegram send. Provides observability
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
                displayGoal = displayGoal.substring("output the following message:".length()).trim();
            } else if (lower.startsWith("output the message:")) {
                displayGoal = displayGoal.substring("output the message:".length()).trim();
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
                "ID: %s | Cron: %s | TZ: %s | RunOnce: %s | " +
                "Goal: %s | Next fire: %s | Status: %s",
                id, cronExpression, timezone, runOnce,
                displayGoal, nextFire, status
        );
    }

}
