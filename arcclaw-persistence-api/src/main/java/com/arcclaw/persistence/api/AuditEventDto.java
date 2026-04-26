package com.arcclaw.persistence.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transfer object for audit events.
 * §1.2: Core-persistence boundary.
 */
public record AuditEventDto(
    UUID id,
    String sessionId,
    String userId,
    String agentRole,
    String goalHash,
    String outcome,
    String eventType,
    String eventDetail,
    int inputTokens,
    int outputTokens,
    BigDecimal usdCost,
    Instant timestamp,
    int iterationsUsed,
    String appVersion,
    String idempotencyKey,
    long sequence,
    String hmacHash,
    String prevHash
) {}
