package com.arcclaw.core.state;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent cron job entity backed by the {@code agentcron} SQLite table.
 * <p>
 * All fields are public (project convention: no getters/setters).
 * Lifecycle: ACTIVE → RUNNING → ACTIVE (recurring) / COMPLETED (one-shot) / FAILED.
 */
@Entity
@Table(name = "agentcron")
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
     * Explicit {@code INTEGER NOT NULL DEFAULT 0} for GraalVM native safety.
     * Hibernate 6 community dialect boolean→INTEGER mapping can be stripped
     * by AOT unless explicitly registered — this DDL guarantees all-mode safety.
     */
    @Column(nullable = false, columnDefinition = "INTEGER NOT NULL DEFAULT 0")
    public boolean runOnce = false;

    public String goal;

    @Convert(converter = InstantToTextConverter.class)
    public Instant nextFireTime;

    @Convert(converter = InstantToTextConverter.class)
    public Instant lastFireTime;

    @Convert(converter = CronStatusConverter.class)
    public CronStatus status = CronStatus.ACTIVE;

    public String errorReason;

    /**
     * Idempotency key — set BEFORE Telegram send. Provides observability
     * for at-least-once delivery semantics. If the app crashes before delivery,
     * the job remains ACTIVE and will be retried on next poll.
     */
    public String lastDeliveryId;

    @Convert(converter = InstantToTextConverter.class)
    public Instant createdAt;

    @Convert(converter = InstantToTextConverter.class)
    public Instant updatedAt;

    /**
     * Canonical display string used by reflexes and tools.
     */
    public String toDisplayString() {
        return String.format(
                "ID: %s | Cron: %s | TZ: %s | RunOnce: %s | " +
                "Goal: %s | Next fire: %s | Status: %s",
                id, cronExpression, timezone, runOnce,
                goal, nextFireTime, status
        );
    }
}
