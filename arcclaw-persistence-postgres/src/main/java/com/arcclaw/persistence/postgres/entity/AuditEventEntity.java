package com.arcclaw.persistence.postgres.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AuditEvent;

@Entity(name = "AuditEvent")
@Table(name = "audit_event")
public class AuditEventEntity implements AuditEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String sessionId;

    @Column(updatable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private String agentRole;

    @Column(nullable = false, updatable = false, length = 64)
    private String goalHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SecurityEventType eventType;

    @Column(updatable = false, length = 512)
    private String eventDetail;

    @Column(nullable = false, updatable = false)
    private int inputTokens;

    @Column(nullable = false, updatable = false)
    private int outputTokens;

    @Column(nullable = false, updatable = false, precision = 10, scale = 6)
    private BigDecimal usdCost;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @Column(updatable = false)
    private int iterationsUsed;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "idempotency_key", updatable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private long sequence;

    @Column(nullable = false, updatable = false, length = 64)
    private String hmacHash;

    @Column(nullable = false, updatable = false, length = 64)
    private String prevHash;

    public AuditEventEntity() {}

    public static AuditEventEntity of(String sessionId, String userId, String agentRole,
                                      String goalHash, AuditOutcome outcome, SecurityEventType eventType,
                                      int inputTokens, int outputTokens,
                                      BigDecimal usdCost, int iterationsUsed, String eventDetail) {
        var e = new AuditEventEntity();
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
        e.sequence       = 0;
        e.timestamp      = Instant.now();
        return e;
    }

    public static AuditEventEntity securityEvent(String sessionId, String userId,
                                                  String agentRole, SecurityEventType type,
                                                  String detail) {
        var e = new AuditEventEntity();
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
        e.sequence       = 0;
        e.timestamp      = Instant.now();
        return e;
    }

    @Override
    public String computePayloadStr() {
        return id.toString() + "|" + sessionId + "|" + eventType + "|" +
               (eventDetail != null ? eventDetail : "") + "|" + timestamp.toEpochMilli();
    }

    @Override public UUID getId() { return id; }
    @Override public void setId(UUID id) { this.id = id; }

    @Override public String getSessionId() { return sessionId; }
    @Override public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    @Override public String getUserId() { return userId; }
    @Override public void setUserId(String userId) { this.userId = userId; }

    @Override public String getAgentRole() { return agentRole; }
    @Override public void setAgentRole(String agentRole) { this.agentRole = agentRole; }

    @Override public String getGoalHash() { return goalHash; }
    @Override public void setGoalHash(String goalHash) { this.goalHash = goalHash; }

    @Override public AuditOutcome getOutcome() { return outcome; }
    @Override public void setOutcome(AuditOutcome outcome) { this.outcome = outcome; }

    @Override public SecurityEventType getEventType() { return eventType; }
    @Override public void setEventType(SecurityEventType eventType) { this.eventType = eventType; }

    @Override public String getEventDetail() { return eventDetail; }
    @Override public void setEventDetail(String eventDetail) { this.eventDetail = eventDetail; }

    @Override public int getInputTokens() { return inputTokens; }
    @Override public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    @Override public int getOutputTokens() { return outputTokens; }
    @Override public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    @Override public BigDecimal getUsdCost() { return usdCost; }
    @Override public void setUsdCost(BigDecimal usdCost) { this.usdCost = usdCost; }

    @Override public Instant getTimestamp() { return timestamp; }
    @Override public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    @Override public int getIterationsUsed() { return iterationsUsed; }
    @Override public void setIterationsUsed(int iterationsUsed) { this.iterationsUsed = iterationsUsed; }

    @Override public String getAppVersion() { return appVersion; }
    @Override public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    @Override public String getIdempotencyKey() { return idempotencyKey; }
    @Override public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    @Override public long getSequence() { return sequence; }
    @Override public void setSequence(long sequence) { this.sequence = sequence; }

    @Override public String getHmacHash() { return hmacHash; }
    @Override public void setHmacHash(String hmacHash) { this.hmacHash = hmacHash; }

    @Override public String getPrevHash() { return prevHash; }
    @Override public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
}


