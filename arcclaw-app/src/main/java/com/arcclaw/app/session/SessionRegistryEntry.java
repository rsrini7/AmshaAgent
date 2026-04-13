package com.arcclaw.app.session;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;


/**
 * JPA entity mapping the (channelType, channelId, userId) triplet to a stable,
 * random session ID.
 * <p>
 * Replaces the UUID v3 (MD5) deterministic derivation that previously lived in
 * {@code SessionResolutionHandler}. UUID v4 (random) is used here to align with
 * the AES-GCM-SIV + Argon2id security posture used everywhere else.
 */
@Entity
@Table(name = "session_registry",
       uniqueConstraints = @UniqueConstraint(columnNames = {"channel_type", "channel_id", "user_id"}))
public class SessionRegistryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "channel_type", nullable = false)
    public String channelType;

    @Column(name = "channel_id", nullable = false)
    public String channelId;

    @Column(name = "user_id", nullable = false)
    public String userId;

    /** Stable random UUID assigned on first contact. Never changes. */
    @Column(name = "session_id", nullable = false, unique = true)
    public String sessionId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
