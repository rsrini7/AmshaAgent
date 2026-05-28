package com.arcclaw.core.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.SecurityEventType;

public interface AuditEvent {

    UUID getId();

    void setId(UUID id);

    String getSessionId();

    void setSessionId(String sessionId);

    String getUserId();

    void setUserId(String userId);

    String getAgentRole();

    void setAgentRole(String agentRole);

    String getGoalHash();

    void setGoalHash(String goalHash);

    AuditOutcome getOutcome();

    void setOutcome(AuditOutcome outcome);

    SecurityEventType getEventType();

    void setEventType(SecurityEventType eventType);

    String getEventDetail();

    void setEventDetail(String eventDetail);

    int getInputTokens();

    void setInputTokens(int inputTokens);

    int getOutputTokens();

    void setOutputTokens(int outputTokens);

    BigDecimal getUsdCost();

    void setUsdCost(BigDecimal usdCost);

    Instant getTimestamp();

    void setTimestamp(Instant timestamp);

    int getIterationsUsed();

    void setIterationsUsed(int iterationsUsed);

    String getAppVersion();

    void setAppVersion(String appVersion);

    String getIdempotencyKey();

    void setIdempotencyKey(String idempotencyKey);

    long getSequence();

    void setSequence(long sequence);

    String getHmacHash();

    void setHmacHash(String hmacHash);

    String getPrevHash();

    void setPrevHash(String prevHash);

    String computePayloadStr();

    // ── Wither methods ────────────────────────────────────────────────────

    default AuditEvent withId(UUID id) {
        setId(id);
        return this;
    }

    default AuditEvent withSessionId(String s) {
        setSessionId(s);
        return this;
    }

    default AuditEvent withUserId(String u) {
        setUserId(u);
        return this;
    }

    default AuditEvent withAgentRole(String r) {
        setAgentRole(r);
        return this;
    }

    default AuditEvent withGoalHash(String h) {
        setGoalHash(h);
        return this;
    }

    default AuditEvent withOutcome(AuditOutcome o) {
        setOutcome(o);
        return this;
    }

    default AuditEvent withEventType(SecurityEventType t) {
        setEventType(t);
        return this;
    }

    default AuditEvent withEventDetail(String d) {
        setEventDetail(d);
        return this;
    }

    default AuditEvent withInputTokens(int t) {
        setInputTokens(t);
        return this;
    }

    default AuditEvent withOutputTokens(int t) {
        setOutputTokens(t);
        return this;
    }

    default AuditEvent withUsdCost(BigDecimal c) {
        setUsdCost(c);
        return this;
    }

    default AuditEvent withTimestamp(Instant t) {
        setTimestamp(t);
        return this;
    }

    default AuditEvent withIterationsUsed(int i) {
        setIterationsUsed(i);
        return this;
    }

    default AuditEvent withAppVersion(String v) {
        setAppVersion(v);
        return this;
    }

    default AuditEvent withIdempotencyKey(String k) {
        setIdempotencyKey(k);
        return this;
    }

    default AuditEvent withSequence(long s) {
        setSequence(s);
        return this;
    }

    default AuditEvent withHmacHash(String h) {
        setHmacHash(h);
        return this;
    }

    default AuditEvent withPrevHash(String h) {
        setPrevHash(h);
        return this;
    }
}
