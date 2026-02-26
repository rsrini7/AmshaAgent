package com.arcclaw.core.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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

    public CompactionSummaryEntity() {
    }

    public CompactionSummaryEntity(String sessionId, String summary, Instant compactedAt, int messagesCompacted) {
        this.sessionId = sessionId;
        this.summary = summary;
        this.compactedAt = compactedAt;
        this.messagesCompacted = messagesCompacted;
    }
}
