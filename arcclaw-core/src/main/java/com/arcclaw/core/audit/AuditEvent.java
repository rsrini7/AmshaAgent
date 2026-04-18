package com.arcclaw.core.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import com.arcclaw.core.agent.AgentSentinels;

/**
 * Immutable WORM audit record. Insert-only — no update or delete operations permitted.
 * Includes an HMAC chain for tamper detection.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @Column(nullable = false, updatable = false)
    public UUID id;

    @Column(nullable = false, updatable = false)
    public String sessionId;

    @Column(updatable = false)
    public String userId;

    @Column(nullable = false, updatable = false)
    public String agentRole;

    @Column(nullable = false, updatable = false, length = 64)
    public String goalHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    public AuditOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    public SecurityEventType eventType;

    @Column(updatable = false, length = 512)
    public String eventDetail;

    @Column(nullable = false, updatable = false)
    public int inputTokens;

    @Column(nullable = false, updatable = false)
    public int outputTokens;

    @Column(nullable = false, updatable = false, precision = 10, scale = 6)
    public BigDecimal usdCost;

    @Column(nullable = false, updatable = false)
    public Instant timestamp;

    @Column(updatable = false)
    public int iterationsUsed;

    @Column(name = "app_version", length = 32)
    public String appVersion;
    
    @Column(name = "idempotency_key", updatable = false, length = 128)
    public String idempotencyKey;

    // Monotonic per-session insert order — set exclusively by AuditEventRepository.record()
    @Column(nullable = false, updatable = false)
    public long sequence;

    // HMAC Chain Fields
    @Column(nullable = false, updatable = false, length = 64)
    public String hmacHash;

    @Column(nullable = false, updatable = false, length = 64)
    public String prevHash;

    public AuditEvent() {} // JPA only

    public static AuditEvent of(String sessionId, String userId, String agentRole,
                                 String goalHash, AuditOutcome outcome, SecurityEventType eventType,
                                 int inputTokens, int outputTokens,
                                 BigDecimal usdCost, int iterationsUsed, String eventDetail) {
        var e = new AuditEvent();
        e.id             = UUID.randomUUID();
        e.sessionId      = sessionId;
        e.userId         = userId;
        e.agentRole      = agentRole;
        e.goalHash       = goalHash;
        e.outcome        = outcome;
        e.eventType      = eventType;
        e.eventDetail    = eventDetail;
        e.inputTokens    = inputTokens;
        e.outputTokens   = outputTokens;
        e.usdCost        = usdCost;
        e.iterationsUsed = iterationsUsed;
        e.sequence       = 0; // placeholder — set by AuditEventRepository.record() before persist
        e.timestamp      = Instant.now();
        return e;
    }

    public static AuditEvent securityEvent(String sessionId, String userId,
                                            String agentRole, SecurityEventType type,
                                            String detail) {
        var e = new AuditEvent();
        e.id             = UUID.randomUUID();
        e.sessionId      = sessionId != null ? sessionId : "unknown";
        e.userId         = userId;
        e.agentRole      = agentRole != null ? agentRole : AgentSentinels.SYSTEM_ROLE;
        e.goalHash       = "n/a";
        e.outcome        = AuditOutcome.SECURITY_EVENT;
        e.eventType      = type;
        e.eventDetail    = detail;
        e.inputTokens    = 0;
        e.outputTokens   = 0;
        e.usdCost        = BigDecimal.ZERO;
        e.iterationsUsed = 0;
        e.sequence       = 0; // placeholder — set by AuditEventRepository.record() before persist
        e.timestamp      = Instant.now();
        return e;
    }

    /**
     * Reconstructs the canonical string payload used for HMAC computation.
     */
    public String computePayloadStr() {
        return id.toString() + "|" + sessionId + "|" + eventType + "|" + 
               (eventDetail != null ? eventDetail : "") + "|" + timestamp.toEpochMilli();
    }
}
