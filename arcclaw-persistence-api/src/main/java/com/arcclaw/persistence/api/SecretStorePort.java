package com.arcclaw.persistence.api;

import java.util.Optional;

/**
 * Port for persisting and managing encrypted secrets.
 * §1.2: Core-persistence boundary.
 */
public interface SecretStorePort {
    void save(SecretDto dto);

    Optional<SecretDto> find(String sessionId, String keyName);

    void delete(String sessionId, String keyName);

    void deleteBySessionId(String sessionId);
}
