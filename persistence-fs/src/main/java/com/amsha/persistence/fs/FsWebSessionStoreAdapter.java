package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsWebSessionStoreAdapter implements WebSessionStorePort {

    private static final String DOMAIN = "web-sessions";

    @Inject
    FilesystemFileStore store;

    @Override
    public boolean save(WebSessionDto session) {
        List<String> path = List.of(DOMAIN, session.sessionId() + ".json");
        Optional<WebSessionDto> existing = store.readOptional(path, WebSessionDto.class);
        if (existing.isEmpty()) {
            store.write(path, session);
            return true;
        }
        if (existing.get().revision() != session.revision()) {
            return false;
        }
        WebSessionDto toWrite = new WebSessionDto(
                session.sessionId(),
                session.userId(),
                session.tokenDigest(),
                session.csrfDigest(),
                session.idleExpiresAt(),
                session.absoluteExpiresAt(),
                session.rotatedAt(),
                session.revokedAt(),
                session.safeClientMetadata(),
                session.revision() + 1,
                session.createdAt(),
                session.updatedAt());
        store.write(path, toWrite);
        return true;
    }

    @Override
    public Optional<WebSessionDto> findById(String sessionId) {
        return store.readOptional(List.of(DOMAIN, sessionId + ".json"), WebSessionDto.class);
    }

    @Override
    public Optional<WebSessionDto> findByTokenDigest(String tokenDigest) {
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), WebSessionDto.class))
                .flatMap(Optional::stream)
                .filter(sess -> tokenDigest.equals(sess.tokenDigest()))
                .findFirst();
    }

    @Override
    public List<WebSessionDto> listByUserId(String userId) {
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), WebSessionDto.class))
                .flatMap(Optional::stream)
                .filter(sess -> userId.equals(sess.userId()))
                .toList();
    }

    @Override
    public int deleteById(String sessionId) {
        boolean deleted = store.delete(List.of(DOMAIN, sessionId + ".json"));
        return deleted ? 1 : 0;
    }

    @Override
    public int deleteByUserId(String userId) {
        List<WebSessionDto> sessions = listByUserId(userId);
        int count = 0;
        for (WebSessionDto session : sessions) {
            if (store.delete(List.of(DOMAIN, session.sessionId() + ".json"))) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int deleteExpired(Instant now) {
        List<String> files = store.list(List.of(DOMAIN), ".json");
        int count = 0;
        for (String file : files) {
            Optional<WebSessionDto> session = store.readOptional(List.of(DOMAIN, file), WebSessionDto.class);
            if (session.isPresent()) {
                WebSessionDto s = session.get();
                if (s.idleExpiresAt().isBefore(now) || s.absoluteExpiresAt().isBefore(now)) {
                    if (store.delete(List.of(DOMAIN, file))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
