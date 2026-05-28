package com.arcclaw.persistence.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistent entity for cron job execution history.
 * One row per actual cron run.
 */
@Entity
@Table(name = "agent_cron_run")
public class CronRunEntity {

    @Id
    public String id;

    public String cronId;

    public String sessionId;

    public String status;

    public Instant startedAt;

    public Instant finishedAt;

    @Column(columnDefinition = "TEXT")
    public String errorReason;

    public int iterationsUsed;

    public int inputTokens;

    public int outputTokens;

    @Column(precision = 10, scale = 6)
    public BigDecimal usdCost;

    public Instant createdAt;
}
