package com.arcclaw.core.state;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

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
        "agentRole" }))
public class AgentCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** The workspace session ID this checkpoint belongs to. */
    @Column(nullable = false, length = 128)
    public String sessionId;

    /** The agent role (e.g., "coordinator", "debugger", "researcher", "coder"). */
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

    @Column(name = "pending_tool_name")
    private String pendingToolName;

    @Column(name = "pending_tool_parameters", columnDefinition = "TEXT")
    private String pendingToolParametersJson;

    /** When this checkpoint was first created. */
    @Column(nullable = false, updatable = false)
    public LocalDateTime createdAt;

    /** When this checkpoint was last updated. */
    @Column(nullable = false)
    public LocalDateTime updatedAt;

    @PrePersist
    void onPersist() {
        var now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getPendingToolName() {
        return pendingToolName;
    }

    public void setPendingToolName(String pendingToolName) {
        this.pendingToolName = pendingToolName;
    }

    public String getPendingToolParametersJson() {
        return pendingToolParametersJson;
    }

    public void setPendingToolParametersJson(String pendingToolParametersJson) {
        this.pendingToolParametersJson = pendingToolParametersJson;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public CheckpointStatus getStatus() {
        return status;
    }

    public void setStatus(CheckpointStatus status) {
        this.status = status;
    }
}
