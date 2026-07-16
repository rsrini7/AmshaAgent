package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for managing browser web sessions.
 */
public interface WebSessionStorePort {
    boolean save(WebSessionDto session);

    Optional<WebSessionDto> findById(String sessionId);

    Optional<WebSessionDto> findByTokenDigest(String tokenDigest);

    List<WebSessionDto> listByUserId(String userId);

    int deleteById(String sessionId);

    int deleteByUserId(String userId);

    int deleteExpired(Instant now);
}
