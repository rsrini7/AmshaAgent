package com.amsha.persistence.api;

public interface CheckpointTransitionPort {

    void transitionStatus(Long id, String newStatus);

    boolean atomicTransition(Long id, String expectedStatus, String newStatus);

    boolean claimForResume(String sessionId, String agentRole);

    boolean claimForRetry(Long id);

    void releaseResumeClaim(String sessionId, String agentRole);
}
