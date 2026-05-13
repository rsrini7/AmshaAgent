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
}
