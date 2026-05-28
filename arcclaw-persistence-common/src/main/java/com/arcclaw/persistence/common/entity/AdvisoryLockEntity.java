package com.arcclaw.persistence.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entity for table-based distributed locks (§T5-3).
 * Shared across SQL databases (SQLite, PostgreSQL).
 */
@Entity
@Table(name = "advisory_lock")
public class AdvisoryLockEntity {

    @Id
    @Column(name = "lock_key")
    private String lockKey;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public AdvisoryLockEntity() {}

    public AdvisoryLockEntity(String lockKey, String ownerId, Instant expiresAt) {
        this.lockKey = lockKey;
        this.ownerId = ownerId;
        this.expiresAt = expiresAt;
    }

    public String getLockKey() {
        return lockKey;
    }

    public void setLockKey(String lockKey) {
        this.lockKey = lockKey;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
