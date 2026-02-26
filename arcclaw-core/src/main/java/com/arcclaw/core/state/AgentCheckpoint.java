package com.arcclaw.core.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
    private Long id;

    @Version
    @Column(nullable = false)
    private int version;

    /** The workspace session ID this checkpoint belongs to. */
    @Column(nullable = false, length = 128)
    private String sessionId;

    /** The agent role (e.g., "coordinator", "debugger", "researcher", "coder"). */
    @Column(nullable = false, length = 64)
    private String agentRole;

    /** Current lifecycle status of the checkpoint. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CheckpointStatus status;

    /**
     * Jackson-serialized {@code List<ReasoningStep>}.
     * <p>
     * Stored as standard TEXT — no {@code @Lob} for SQLite compatibility.
     */
    @Column(columnDefinition = "TEXT")
    private String stateJson;

    /** Timestamp when a failure occurred (null if not failed). */
    private Instant failedAt;

    /** Human-readable failure reason (null if not failed). */
    @Column(length = 1024)
    private String failReason;

    @Column(name = "pending_tool_name")
    private String pendingToolName;

    @Column(name = "pending_tool_parameters", columnDefinition = "TEXT")
    @JsonIgnore // Privacy Fix Phase 41: ensure sensitive tool parameters never leak to telemetry/JSON APIs
    private String pendingToolParametersJson;

    /** When this checkpoint was first created. */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When this checkpoint was last updated. */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
