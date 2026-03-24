package com.arcclaw.core.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;

public record AuditEventResponse(
        UUID id,
        String sessionId,
        String userId,
        String agentRole,
        String goalHash,
        AuditOutcome outcome,
        SecurityEventType eventType,
        String eventDetail,
        int inputTokens,
        int outputTokens,
        BigDecimal usdCost,
        int iterationsUsed,
        Instant timestamp,
        String hmacHash
) {}
