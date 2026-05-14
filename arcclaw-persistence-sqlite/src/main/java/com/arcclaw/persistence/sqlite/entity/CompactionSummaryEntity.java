package com.arcclaw.persistence.sqlite.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "compaction_summary")
public class CompactionSummaryEntity {

    @Id
    private String sessionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String summary;

    private Instant compactedAt;

    private int messagesCompacted;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at")
    private Instant createdAt;

    public CompactionSummaryEntity() {
    }

    public CompactionSummaryEntity(String sessionId, String summary, Instant compactedAt,
                                   int messagesCompacted, String userId, Instant createdAt) {
        this.sessionId = sessionId;
        this.summary = summary;
        this.compactedAt = compactedAt;
        this.messagesCompacted = messagesCompacted;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Instant getCompactedAt() { return compactedAt; }
    public void setCompactedAt(Instant compactedAt) { this.compactedAt = compactedAt; }

    public int getMessagesCompacted() { return messagesCompacted; }
    public void setMessagesCompacted(int messagesCompacted) { this.messagesCompacted = messagesCompacted; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

