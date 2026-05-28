package com.arcclaw.persistence.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "compaction_summary")
public class CompactionSummaryEntity {

    @Id
    @Column(name = "session_id")
    private String sessionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;

    @Column(name = "compacted_at", nullable = false)
    private Instant compactedAt;

    @Column(name = "messages_compacted", nullable = false)
    private int messagesCompacted;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "tenant_id")
    private String tenantId;

    public CompactionSummaryEntity() {}

    public CompactionSummaryEntity(
            String sessionId,
            String summary,
            Instant compactedAt,
            int messagesCompacted,
            String userId,
            String tenantId) {
        this.sessionId = sessionId;
        this.summary = summary;
        this.compactedAt = compactedAt;
        this.messagesCompacted = messagesCompacted;
        this.userId = userId;
        this.tenantId = tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCompactedAt() {
        return compactedAt;
    }

    public void setCompactedAt(Instant compactedAt) {
        this.compactedAt = compactedAt;
    }

    public int getMessagesCompacted() {
        return messagesCompacted;
    }

    public void setMessagesCompacted(int messagesCompacted) {
        this.messagesCompacted = messagesCompacted;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
