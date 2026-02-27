package com.arcclaw.core.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "compaction_summary")
public class CompactionSummaryEntity {

    @Id
    public String sessionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String summary;

    public Instant compactedAt;

    public int messagesCompacted;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "created_at")
    public Instant createdAt;

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

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
