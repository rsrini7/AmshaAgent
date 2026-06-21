package com.arcclaw.persistence.fs;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
public class FsCheckpointHelper {

    static final String DOMAIN = "checkpoints";
    static final String STEPS = "steps";

    private final AtomicLong idSeq = new AtomicLong(0);

    @Inject
    FilesystemFileStore store;

    @PostConstruct
    void initIdSeq() {
        long maxId = 0;
        for (String session : store.listDirs(List.of(DOMAIN))) {
            for (String roleFile : store.list(List.of(DOMAIN, session), ".json")) {
                if (roleFile.equals("index.json")) continue;
                Optional<CheckpointDto> cp =
                        store.readOptional(List.of(DOMAIN, session, roleFile), CheckpointDto.class);
                if (cp.isPresent() && cp.get().id() != null && cp.get().id() > maxId) {
                    maxId = cp.get().id();
                }
            }
        }
        idSeq.set(maxId);
    }

    long nextId() {
        return idSeq.incrementAndGet();
    }

    List<CheckpointDto> loadSession(String sessionId) {
        return store.list(List.of(DOMAIN, sessionId), ".json").stream()
                .filter(f -> !f.equals("index.json"))
                .map(f -> store.readOptional(List.of(DOMAIN, sessionId, f), CheckpointDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    List<CheckpointDto> loadAll() {
        List<CheckpointDto> all = new ArrayList<>();
        for (String session : store.listDirs(List.of(DOMAIN))) {
            all.addAll(loadSession(session));
        }
        return all;
    }

    void persistSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        String cpIdStr = checkpointId.toString();
        for (int i = 0; i < steps.size(); i++) {
            store.write(List.of(DOMAIN, STEPS, cpIdStr, (i + 1) + ".json"), steps.get(i));
        }
    }

    void deleteStepsForSession(String sessionId, String role) {
        findBySessionAndRole(sessionId, role)
                .ifPresent(cp ->
                        store.deleteDirectory(List.of(DOMAIN, STEPS, cp.id().toString())));
    }

    Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        return store.readOptional(List.of(DOMAIN, sessionId, sanitizeRole(agentRole) + ".json"), CheckpointDto.class);
    }

    static String sanitizeRole(String role) {
        return role.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static String updateJsonMap(String json, String key, String value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> map;
            if (json == null || json.isBlank() || json.equals("{}")) {
                map = new LinkedHashMap<>();
            } else {
                map = mapper.readValue(json, new TypeReference<Map<String, String>>() {});
            }
            map.put(key, value);
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return json;
        }
    }
}
