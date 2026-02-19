package com.arcclaw.core.state;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for persisting agent reasoning loop state.
 * <p>
 * Each checkpoint captures the full serialized reasoning history
 * ({@code stateJson}) for a given session and agent role, enabling
 * crash recovery and audit trails.
 * <p>
 * <strong>Design note:</strong> Uses plain JPA {@code @Entity} (not
 * {@code PanacheEntity}) because the entity lives in {@code arcclaw-core}
 * while the Quarkus build plugin runs in {@code arcclaw-app}. Panache
 * bytecode enhancement doesn't cross module boundaries. The repository
 * pattern ({@link AgentCheckpointRepository}) provides the Panache API.
 * <p>
 * <strong>SQLite note:</strong> Uses {@code TEXT} column (not {@code @Lob})
 * for better SQLite compatibility.
 */
@Entity
@Table(name = "agent_checkpoint", uniqueConstraints = @UniqueConstraint(name = "uq_checkpoint_session_role", columnNames = {
        "sessionId",
        "agentRole" }), indexes = @Index(name = "idx_checkpoint_session_role", columnList = "sessionId, agentRole"))
public class AgentCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The workspace session ID this checkpoint belongs to. */
    @Column(nullable = false, length = 128)
    public String sessionId;

    /** The agent role (e.g., "grok", "coder", "researcher"). */
    @Column(nullable = false, length = 64)
    public String agentRole;

    /** Current lifecycle status of the checkpoint. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public CheckpointStatus status;

    /**
     * Jackson-serialized {@code List<ReasoningStep>}.
     * <p>
     * Stored as standard TEXT — no {@code @Lob} for SQLite compatibility.
     */
    @Column(columnDefinition = "TEXT")
    public String stateJson;

    /** Timestamp when a failure occurred (null if not failed). */
    public Instant failedAt;

    /** Human-readable failure reason (null if not failed). */
    @Column(length = 1024)
    public String failReason;

    /** When this checkpoint was first created. */
    @Column(nullable = false, updatable = false)
    public Instant createdAt;

    /** When this checkpoint was last updated. */
    @Column(nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onPersist() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
