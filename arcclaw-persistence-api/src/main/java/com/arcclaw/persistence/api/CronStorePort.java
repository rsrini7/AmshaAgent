package com.arcclaw.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    boolean claimForFire(String id);
    void releaseClaimToActive(String id);
    void markFired(String id, Instant lastFireTime, Instant nextFireTime);
    void markCompleted(String id, Instant lastFireTime);
    void markAwaitingApproval(String id);
    void setDeliveryId(String id, String deliveryId);
    void markFailed(String id, String errorReason);
    void markSkipped(String id, Instant nextFireTime, Instant now);
    void reclaimStaleCrons(Instant threshold);

    void save(AgentCronDto dto);
    int deleteByIdAndUserId(String id, String userId);
    long deleteAllByUserId(String userId);
    long deleteAllBySessionId(String sessionId);
}
