package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for persisting and managing agent cron jobs.
 * §1.2: Core-persistence boundary.
 */
public interface CronStorePort {
    List<AgentCronDto> findDueCrons();

    List<AgentCronDto> findByUserId(String userId);

    List<AgentCronDto> findAwaitingApprovalByUserId(String userId);

    List<AgentCronDto> findAllAwaitingApproval();

    Optional<AgentCronDto> findById(String id);

    boolean claimForFire(String id, String deliveryId);

    default boolean claimForFire(String id) {
        return claimForFire(id, UUID.randomUUID().toString());
    }

    boolean claimForResume(String id, String deliveryId);

    void releaseClaimToActive(String id);

    void markFired(String id, Instant lastFireTime, Instant nextFireTime);

    void markCompleted(String id, Instant lastFireTime);

    void markAwaitingApproval(String id);

    void markRunning(String id);

    void setDeliveryId(String id, String deliveryId);

    void markFailed(String id, String errorReason);

    void markSkipped(String id, Instant nextFireTime, Instant now);

    void reclaimStaleCrons(Instant threshold);

    boolean renewLease(String id, String deliveryId);

    boolean markCompletedIfOwner(String id, String deliveryId, Instant lastFireTime);

    boolean markFiredIfOwner(String id, String deliveryId, Instant lastFireTime, Instant nextFireTime);

    boolean markAwaitingApprovalIfOwner(String id, String deliveryId);

    boolean markAwaitingRetryIfOwner(String id, String deliveryId);

    boolean markFailedIfOwner(String id, String deliveryId, String errorReason);

    boolean markFailedIfStatus(String id, String expectedStatus, String errorReason);

    void save(AgentCronDto dto);

    int deleteByIdAndUserId(String id, String userId);

    long deleteAllByUserId(String userId);

    long deleteAllBySessionId(String sessionId);

    /**
     * Returns all cron jobs, optionally filtered by status.
     * @param status if non-null, filter by this status; if null, return all.
     * @param offset pagination offset (0-based)
     * @param limit  max results to return
     */
    List<AgentCronDto> findAll(String status, int offset, int limit);

    /**
     * Returns total count of cron jobs, optionally filtered by status.
     */
    long countAll(String status);

    /**
     * Returns cron jobs for a specific user, optionally filtered by status.
     */
    List<AgentCronDto> findByUserId(String userId, String status, int offset, int limit);

    /**
     * Returns total count of cron jobs for a specific user, optionally filtered by status.
     */
    long countByUserId(String userId, String status);
}
