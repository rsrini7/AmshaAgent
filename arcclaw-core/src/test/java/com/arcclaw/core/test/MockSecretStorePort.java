package com.arcclaw.core.test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;

@Alternative
@Priority(1)
@ApplicationScoped
public class MockSecretStorePort implements SecretStorePort {

    private final Map<String, SecretDto> store = new ConcurrentHashMap<>();

    @Override 
    public void save(SecretDto dto) {
        store.put(key(dto.sessionId(), dto.keyName()), dto);
    }

    @Override 
    public Optional<SecretDto> find(String sessionId, String keyName) {
        return Optional.ofNullable(store.get(key(sessionId, keyName)));
    }

    @Override 
    public void delete(String sessionId, String keyName) {
        store.remove(key(sessionId, keyName));
    }

    @Override 
    public void deleteBySessionId(String sessionId) {
        store.keySet().removeIf(k -> k.startsWith(sessionId + "|"));
    }

    private String key(String sessionId, String keyName) {
        return sessionId + "|" + keyName;
    }
}
