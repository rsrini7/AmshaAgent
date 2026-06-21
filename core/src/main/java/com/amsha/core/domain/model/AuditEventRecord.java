package com.amsha.core.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.audit.AuditOutcome;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.AuditEventDto;

public final class AuditEventRecord implements AuditEvent {

    private UUID id;
    private String sessionId;
    private String userId;
    private String agentRole;
    private String goalHash;
    private AuditOutcome outcome;
    private SecurityEventType eventType;
    private String eventDetail;
    private int inputTokens;
    private int outputTokens;
    private BigDecimal usdCost;
    private Instant timestamp;
    private int iterationsUsed;
    private String appVersion;
    private String idempotencyKey;
    private long sequence;
    private String hmacHash;
    private String prevHash;

    /** Required no-arg constructor for JDBI RowMapper / Jackson deserialization. */
    public static AuditEventRecord of(
            String sessionId,
            String userId,
            String agentRole,
            String goalHash,
            AuditOutcome outcome,
            SecurityEventType eventType,
            int inputTokens,
            int outputTokens,
            BigDecimal usdCost,
            int iterationsUsed,
            String eventDetail) {
        var r = new AuditEventRecord();
        r.id = UUID.randomUUID();
        r.sessionId = sessionId;
        r.userId = userId;
        r.agentRole = agentRole;
        r.goalHash = goalHash;
        r.outcome = outcome;
        r.eventType = eventType;
        r.eventDetail = eventDetail;
        r.inputTokens = inputTokens;
        r.outputTokens = outputTokens;
        r.usdCost = usdCost;
        r.iterationsUsed = iterationsUsed;
        r.sequence = 0;
        r.timestamp = Instant.now();
        return r;
    }

    public static AuditEventRecord securityEvent(
            String sessionId, String userId, String agentRole, SecurityEventType type, String detail) {
        var r = new AuditEventRecord();
        r.id = UUID.randomUUID();
        r.sessionId = sessionId != null ? sessionId : "unknown";
        r.userId = userId;
        r.agentRole = agentRole != null ? agentRole : AgentSentinels.SYSTEM_ROLE;
        r.goalHash = "n/a";
        r.outcome = AuditOutcome.SECURITY_EVENT;
        r.eventType = type;
        r.eventDetail = detail;
        r.inputTokens = 0;
        r.outputTokens = 0;
        r.usdCost = BigDecimal.ZERO;
        r.iterationsUsed = 0;
        r.sequence = 0;
        r.timestamp = Instant.now();
        return r;
    }

    public static AuditEventRecord fromDto(AuditEventDto dto) {
        AuditEventRecord r = new AuditEventRecord();
        r.id = dto.id();
        r.sessionId = dto.sessionId();
        r.userId = dto.userId();
        r.agentRole = dto.agentRole();
        r.goalHash = dto.goalHash();
        r.outcome = dto.outcome() != null ? AuditOutcome.valueOf(dto.outcome()) : null;
        r.eventType = dto.eventType() != null ? SecurityEventType.valueOf(dto.eventType()) : null;
        r.eventDetail = dto.eventDetail();
        r.inputTokens = dto.inputTokens();
        r.outputTokens = dto.outputTokens();
        r.usdCost = dto.usdCost();
        r.timestamp = dto.timestamp();
        r.iterationsUsed = dto.iterationsUsed();
        r.appVersion = dto.appVersion();
        r.idempotencyKey = dto.idempotencyKey();
        r.sequence = dto.sequence();
        r.hmacHash = dto.hmacHash();
        r.prevHash = dto.prevHash();
        return r;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public void setId(UUID id) {
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
    public String getAgentRole() {
        return agentRole;
    }

    @Override
    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    @Override
    public String getGoalHash() {
        return goalHash;
    }

    @Override
    public void setGoalHash(String goalHash) {
        this.goalHash = goalHash;
    }

    @Override
    public AuditOutcome getOutcome() {
        return outcome;
    }

    @Override
    public void setOutcome(AuditOutcome outcome) {
        this.outcome = outcome;
    }

    @Override
    public SecurityEventType getEventType() {
        return eventType;
    }

    @Override
    public void setEventType(SecurityEventType eventType) {
        this.eventType = eventType;
    }

    @Override
    public String getEventDetail() {
        return eventDetail;
    }

    @Override
    public void setEventDetail(String eventDetail) {
        this.eventDetail = eventDetail;
    }

    @Override
    public int getInputTokens() {
        return inputTokens;
    }

    @Override
    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    @Override
    public int getOutputTokens() {
        return outputTokens;
    }

    @Override
    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    @Override
    public BigDecimal getUsdCost() {
        return usdCost;
    }

    @Override
    public void setUsdCost(BigDecimal usdCost) {
        this.usdCost = usdCost;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public int getIterationsUsed() {
        return iterationsUsed;
    }

    @Override
    public void setIterationsUsed(int iterationsUsed) {
        this.iterationsUsed = iterationsUsed;
    }

    @Override
    public String getAppVersion() {
        return appVersion;
    }

    @Override
    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    @Override
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public long getSequence() {
        return sequence;
    }

    @Override
    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    @Override
    public String getHmacHash() {
        return hmacHash;
    }

    @Override
    public void setHmacHash(String hmacHash) {
        this.hmacHash = hmacHash;
    }

    @Override
    public String getPrevHash() {
        return prevHash;
    }

    @Override
    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    @Override
    public String computePayloadStr() {
        return id.toString() + "|" + sessionId + "|" + eventType + "|" + (eventDetail != null ? eventDetail : "") + "|"
                + timestamp.toEpochMilli();
    }
}
