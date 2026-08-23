package com.amsha.core.hitl;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * In-memory approval lease store for parallel tool batches (ADR-approval-lease-for-parallel-batches).
 *
 * <p>A lease is the tuple {@code (sessionId, toolName, sha256(canonical-sorted-params-json))}
 * recorded when an operator approves a paused call. It only ever substitutes for the human
 * prompt itself inside the deterministic HITL clause of {@code ToolInvocationGuard}; every
 * mandatory check (interceptors, validation, dedup, dynamic admission, role capability) still
 * runs first and every {@code Blocked} return precedes lease consultation. Leases are session-
 * bound optimization hints, never durable consent: they die with the session, expire after a
 * configured TTL, and are bounded by size.
 */
@ApplicationScoped
public class ApprovalLeaseStore {

    private static final Logger log = Logger.getLogger(ApprovalLeaseStore.class);

    /** Hard bound on total leases; eldest-inserted entries are evicted first (LRU-style). */
    static final int MAX_LEASES = 10_000;

    private final Map<LeaseKey, Long> leases = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<LeaseKey> insertionOrder = new ConcurrentLinkedDeque<>();

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "amsha.hitl.approval-lease.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "amsha.hitl.approval-lease.ttl-seconds", defaultValue = "3600")
    long ttlSeconds;

    private long ttlMillis;

    @PostConstruct
    void init() {
        this.ttlMillis = Math.max(1L, ttlSeconds) * 1000L;
    }

    record LeaseKey(String sessionId, String toolName, String paramsHash) {}

    /**
     * Records a lease for the approved call. Failures are telemetry-tier safe: a lease is an
     * optimization hint, so any error degrades to "no lease" (re-prompting) without halting.
     */
    public void grant(String sessionId, String toolName, Map<String, Object> params) {
        if (!enabled || sessionId == null || toolName == null || params == null) {
            return;
        }
        try {
            LeaseKey key = new LeaseKey(sessionId, toolName, canonicalHash(params));
            evictExpired();
            if (leases.size() >= MAX_LEASES && !leases.containsKey(key)) {
                LeaseKey eldest = insertionOrder.pollFirst();
                if (eldest != null) {
                    leases.remove(eldest);
                }
            }
            if (leases.put(key, System.currentTimeMillis() + ttlMillis) == null) {
                insertionOrder.addLast(key);
            }
            log.debugf("HITL lease granted session=%s tool=%s", sessionId, toolName);
        } catch (RuntimeException e) {
            log.warnf("HITL lease grant failed (non-fatal): %s", e.getMessage());
        }
    }

    /**
     * Returns true only when an active, unexpired lease covers exactly this call shape.
     * Emits {@code HITL_LEASE_REUSED} telemetry on every hit (no payload echo).
     */
    public boolean hasActiveLease(
            String sessionId, String agentRole, String userId, String toolName, Map<String, Object> params) {
        if (!enabled || sessionId == null || toolName == null || params == null) {
            return false;
        }
        LeaseKey key;
        try {
            key = new LeaseKey(sessionId, toolName, canonicalHash(params));
        } catch (RuntimeException e) {
            log.warnf("HITL lease lookup failed (non-fatal): %s", e.getMessage());
            return false;
        }
        Long expiresAt = leases.get(key);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiresAt) {
            leases.remove(key);
            return false;
        }
        recordReuse(sessionId, agentRole, userId, toolName);
        return true;
    }

    /** Evicts every lease belonging to the session; wired at workspace teardown. */
    public void clearSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        leases.keySet().removeIf(k -> k.sessionId().equals(sessionId));
        insertionOrder.removeIf(k -> k.sessionId().equals(sessionId));
    }

    int size() {
        return leases.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        leases.entrySet().removeIf(e -> e.getValue() < now);
    }

    private void recordReuse(String sessionId, String agentRole, String userId, String toolName) {
        try {
            auditService.record(sessionId, userId, agentRole, SecurityEventType.HITL_LEASE_REUSED, "tool=" + toolName);
        } catch (RuntimeException | LinkageError _) {
            // Telemetry tier: a failed audit write must never halt the loop.
        }
    }

    /**
     * SHA-256 over the JSON serialization with lexicographically sorted keys, so semantically
     * identical parameter maps hash identically regardless of map iteration order. Any param
     * drift produces a different hash and therefore re-prompts.
     */
    private String canonicalHash(Map<String, Object> params) {
        Objects.requireNonNull(params);
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(sorted);
        } catch (Exception e) {
            // Deterministic fallback that still sorts keys.
            canonical = sorted.toString();
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JRE contract: every implementation must provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
