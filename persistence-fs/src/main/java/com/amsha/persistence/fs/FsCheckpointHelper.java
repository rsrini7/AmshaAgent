package com.amsha.persistence.fs;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.ReasoningStepDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsCheckpointHelper {

    static final String DOMAIN = "checkpoints";
    static final String STEPS = "steps";
    static final String ENVELOPES = "envelopes";
    static final String ATTEMPT_LINKS = "attempt_links";
    private static final Set<String> RESERVED_DIRS = Set.of(STEPS, ENVELOPES, ATTEMPT_LINKS);

    private final AtomicLong idSeq = new AtomicLong(0);

    @Inject
    FilesystemFileStore store;

    @PostConstruct
    void initIdSeq() {
        long maxId = 0;
        for (String session : store.listDirs(List.of(DOMAIN))) {
            if (RESERVED_DIRS.contains(session) || session.startsWith(".")) continue;
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
        if (RESERVED_DIRS.contains(sessionId) || sessionId.startsWith(".")) {
            return List.of();
        }
        Map<Long, CheckpointDto> checkpoints = new LinkedHashMap<>();
        for (CheckpointDto checkpoint : loadSessionProjection(sessionId)) {
            checkpoints.put(checkpoint.id(), checkpoint);
        }
        loadEnvelopes().stream()
                .map(FsCheckpointEnvelope::checkpoint)
                .filter(checkpoint -> sessionId.equals(checkpoint.sessionId()))
                .forEach(checkpoint -> checkpoints.put(checkpoint.id(), checkpoint));
        return List.copyOf(checkpoints.values());
    }

    private List<CheckpointDto> loadSessionProjection(String sessionId) {
        return store.list(List.of(DOMAIN, sessionId), ".json").stream()
                .filter(fileName -> !fileName.equals("index.json"))
                .map(fileName -> store.readOptional(List.of(DOMAIN, sessionId, fileName), CheckpointDto.class))
                .flatMap(Optional::stream)
                .map(this::resolveAuthoritativeCheckpoint)
                .toList();
    }

    List<CheckpointDto> loadAll() {
        Map<Long, CheckpointDto> checkpoints = new LinkedHashMap<>();
        for (String session : store.listDirs(List.of(DOMAIN))) {
            if (RESERVED_DIRS.contains(session) || session.startsWith(".")) continue;
            for (CheckpointDto checkpoint : loadSessionProjection(session)) {
                checkpoints.put(checkpoint.id(), checkpoint);
            }
        }
        loadEnvelopes().stream()
                .map(FsCheckpointEnvelope::checkpoint)
                .forEach(checkpoint -> checkpoints.put(checkpoint.id(), checkpoint));
        return List.copyOf(checkpoints.values());
    }

    private List<FsCheckpointEnvelope> loadEnvelopes() {
        return new FsCheckpointLinkIndex(this, store).loadAll();
    }

    <T> T withCheckpointLock(Supplier<T> action) {
        return FsCheckpointLock.withLock(store, action);
    }

    Optional<CheckpointDto> findById(Long id) {
        if (id == null) return Optional.empty();
        Optional<FsCheckpointEnvelope> env = loadEnvelope(id);
        if (env.isPresent() && env.get().checkpoint() != null) {
            return Optional.of(env.get().checkpoint());
        }
        return loadAll().stream().filter(c -> id.equals(c.id())).findFirst();
    }

    CheckpointDto resolveAuthoritativeCheckpoint(CheckpointDto cp) {
        if (cp == null || cp.id() == null) return cp;
        return loadEnvelope(cp.id()).map(FsCheckpointEnvelope::checkpoint).orElse(cp);
    }

    void persistSteps(Long checkpointId, List<ReasoningStepDto> steps) {
        if (steps == null) return;
        withCheckpointLock(() -> {
            String checkpointDirectory = checkpointId.toString();
            for (int i = 0; i < steps.size(); i++) {
                store.write(List.of(DOMAIN, STEPS, checkpointDirectory, (i + 1) + ".json"), steps.get(i));
            }
            return null;
        });
    }

    void persistStep(Long checkpointId, int sequenceNum, ReasoningStepDto step) {
        withCheckpointLock(() -> {
            store.write(List.of(DOMAIN, STEPS, checkpointId.toString(), sequenceNum + ".json"), step);
            return null;
        });
    }

    void deleteSteps(Long checkpointId) {
        withCheckpointLock(() -> {
            Optional<FsCheckpointEnvelope> envelope = loadEnvelope(checkpointId);
            if (envelope.isPresent()) {
                FsCheckpointEnvelope current = envelope.get();
                saveEnvelope(new FsCheckpointEnvelope(
                        FsCheckpointEnvelope.CURRENT_VERSION, current.checkpoint(), List.of(), List.of()));
                for (FsAttemptLink link : current.attemptLinks()) {
                    deleteAttemptLink(link.workspaceId(), link.attemptId());
                }
            }
            store.deleteDirectory(List.of(DOMAIN, STEPS, checkpointId.toString()));
            return null;
        });
    }

    void deleteStepsForSession(String sessionId, String role) {
        findBySessionAndRole(sessionId, role).ifPresent(cp -> deleteSteps(cp.id()));
    }

    Optional<FsCheckpointEnvelope> loadEnvelope(Long checkpointId) {
        if (checkpointId == null) return Optional.empty();
        return store.readOptional(List.of(DOMAIN, ENVELOPES, checkpointId + ".json"), FsCheckpointEnvelope.class)
                .map(envelope -> {
                    if (!checkpointId.equals(envelope.checkpoint().id())) {
                        throw new IllegalStateException("Checkpoint envelope identity does not match its file name");
                    }
                    return envelope;
                });
    }

    void saveEnvelope(FsCheckpointEnvelope envelope) {
        withCheckpointLock(() -> {
            Path target = store.resolve(
                    List.of(DOMAIN, ENVELOPES, envelope.checkpoint().id() + ".json"));
            writeAtomicallyWithFsync(target, envelope);
            return null;
        });
    }

    List<FsAttemptLink> findAttemptLinks(String workspaceId, String attemptId) {
        return new FsCheckpointLinkIndex(this, store).find(workspaceId, attemptId);
    }

    Optional<FsAttemptLink> findAttemptLink(String workspaceId, String attemptId) {
        List<FsAttemptLink> links = findAttemptLinks(workspaceId, attemptId);
        return links.isEmpty() ? Optional.empty() : Optional.of(links.getFirst());
    }

    void saveAttemptLink(FsAttemptLink link) {
        withCheckpointLock(() -> {
            String encodedWorkspaceId = FsToolAttemptHelper.encodeWorkspaceId(link.workspaceId());
            Path target = store.resolve(List.of(DOMAIN, ATTEMPT_LINKS, encodedWorkspaceId, link.attemptId() + ".json"));
            writeAtomicallyWithFsync(target, link);
            return null;
        });
    }

    void deleteAttemptLink(String workspaceId, String attemptId) {
        if (workspaceId == null || attemptId == null) return;
        withCheckpointLock(() -> {
            String encodedWorkspaceId = FsToolAttemptHelper.encodeWorkspaceId(workspaceId);
            store.delete(List.of(DOMAIN, ATTEMPT_LINKS, encodedWorkspaceId, attemptId + ".json"));
            return null;
        });
    }

    FsCheckpointEnvelope loadOrCreateEnvelope(CheckpointDto checkpoint) {
        Optional<FsCheckpointEnvelope> existing = loadEnvelope(checkpoint.id());
        if (existing.isPresent()) {
            FsCheckpointEnvelope env = existing.get();
            ensureAttemptLinksIndexed(env);
            return env;
        }
        List<ReasoningStepDto> steps = loadSteps(checkpoint.id());
        return new FsCheckpointEnvelope(FsCheckpointEnvelope.CURRENT_VERSION, checkpoint, steps, List.of());
    }

    void ensureAttemptLinksIndexed(FsCheckpointEnvelope envelope) {
        if (envelope == null) return;
        withCheckpointLock(() -> {
            for (FsAttemptLink link : envelope.attemptLinks()) {
                saveAttemptLink(link);
            }
            return null;
        });
    }

    List<ReasoningStepDto> loadSteps(Long checkpointId) {
        Optional<FsCheckpointEnvelope> env = loadEnvelope(checkpointId);
        if (env.isPresent()) {
            return env.get().steps();
        }
        String cpIdStr = checkpointId.toString();
        return store.list(List.of(DOMAIN, STEPS, cpIdStr), ".json").stream()
                .sorted(Comparator.naturalOrder())
                .map(f -> store.read(List.of(DOMAIN, STEPS, cpIdStr, f), ReasoningStepDto.class))
                .toList();
    }

    void saveCheckpointFile(CheckpointDto dto) {
        withCheckpointLock(() -> {
            String session = dto.sessionId();
            String role = sanitizeRole(dto.agentRole());
            store.write(List.of(DOMAIN, session, role + ".json"), dto);
            return null;
        });
    }

    void writeAtomicallyWithFsync(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            store.objectMapper().writeValue(tmp.toFile(), value);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dirChannel = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                dirChannel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed atomic fsync write: " + target, e);
        }
    }

    CheckpointDto upsertCheckpoint(
            CheckpointDto requested,
            List<ReasoningStepDto> requestedSteps,
            boolean replaceSteps,
            boolean incrementVersion) {
        return withCheckpointLock(() -> {
            Optional<CheckpointDto> existingByKey = findBySessionAndRole(requested.sessionId(), requested.agentRole());
            Long checkpointId = existingByKey
                    .map(CheckpointDto::id)
                    .orElseGet(() -> requested.id() != null ? requested.id() : nextId());
            Optional<FsCheckpointEnvelope> existingEnvelope = loadEnvelope(checkpointId);
            CheckpointDto previous = existingByKey.orElseGet(
                    () -> existingEnvelope.map(FsCheckpointEnvelope::checkpoint).orElse(null));
            if (existingEnvelope.isPresent()
                    && (!Objects.equals(
                                    requested.sessionId(),
                                    existingEnvelope.get().checkpoint().sessionId())
                            || !Objects.equals(
                                    requested.agentRole(),
                                    existingEnvelope.get().checkpoint().agentRole()))) {
                throw new IllegalArgumentException("Checkpoint identity conflicts with the existing envelope");
            }
            int currentVersion = previous != null && previous.version() != null ? previous.version() : 0;
            int nextVersion = incrementVersion && previous != null ? currentVersion + 1 : currentVersion;
            CheckpointDto saved = CheckpointDtoHelper.withIdAndVersion(requested, checkpointId, nextVersion)
                    .ensureTimestamps(previous != null ? previous.createdAt() : requested.createdAt(), Instant.now());
            List<ReasoningStepDto> steps = replaceSteps
                    ? requestedSteps == null ? List.of() : List.copyOf(requestedSteps)
                    : existingEnvelope.map(FsCheckpointEnvelope::steps).orElseGet(() -> loadSteps(checkpointId));
            List<FsAttemptLink> links = replaceSteps
                    ? List.of()
                    : existingEnvelope.map(FsCheckpointEnvelope::attemptLinks).orElse(List.of());
            saveEnvelope(new FsCheckpointEnvelope(FsCheckpointEnvelope.CURRENT_VERSION, saved, steps, links));
            if (replaceSteps && existingEnvelope.isPresent()) {
                existingEnvelope
                        .get()
                        .attemptLinks()
                        .forEach(link -> deleteAttemptLink(link.workspaceId(), link.attemptId()));
            }
            saveCheckpointFile(saved);
            persistSteps(checkpointId, steps);
            return saved;
        });
    }

    void deleteCheckpoint(String sessionId, String role) {
        withCheckpointLock(() -> {
            findBySessionAndRole(sessionId, role).ifPresent(checkpoint -> {
                Optional<FsCheckpointEnvelope> envelope = loadEnvelope(checkpoint.id());
                envelope.ifPresent(value ->
                        value.attemptLinks().forEach(link -> deleteAttemptLink(link.workspaceId(), link.attemptId())));
                store.delete(List.of(DOMAIN, sessionId, sanitizeRole(role) + ".json"));
                store.deleteDirectory(List.of(DOMAIN, STEPS, checkpoint.id().toString()));
                store.delete(List.of(DOMAIN, ENVELOPES, checkpoint.id() + ".json"));
            });
            return null;
        });
    }

    void purgeOldChatHistory(Instant cutoff) {
        withCheckpointLock(() -> {
            for (CheckpointDto checkpoint : loadAll()) {
                if (checkpoint.updatedAt() == null || !checkpoint.updatedAt().isBefore(cutoff)) continue;
                Optional<FsCheckpointEnvelope> envelope = loadEnvelope(checkpoint.id());
                store.delete(List.of(DOMAIN, ENVELOPES, checkpoint.id() + ".json"));
                envelope.ifPresent(value ->
                        value.attemptLinks().forEach(link -> deleteAttemptLink(link.workspaceId(), link.attemptId())));
                store.delete(List.of(DOMAIN, checkpoint.sessionId(), sanitizeRole(checkpoint.agentRole()) + ".json"));
                store.deleteDirectory(List.of(DOMAIN, STEPS, checkpoint.id().toString()));
            }
            return null;
        });
    }

    CheckpointDto buildUpsertCheckpoint(
            CheckpointDto existing,
            String sessionId,
            String agentRole,
            String autonomy,
            String stateJson,
            int iterationsUsed,
            String originalGoal,
            String contextDataJson) {
        boolean preserveAwaiting = existing != null
                && (CheckpointStatus.AWAITING_APPROVAL.name().equals(existing.status())
                        || CheckpointStatus.RESUMING.name().equals(existing.status()));
        String status = preserveAwaiting ? existing.status() : CheckpointStatus.IN_PROGRESS.name();
        String batchDecisions = preserveAwaiting ? existing.batchToolDecisions() : null;
        String goal = (originalGoal != null && !originalGoal.isBlank())
                ? originalGoal
                : (existing != null ? existing.originalGoal() : null);
        Instant now = Instant.now();
        return new CheckpointDto(
                existing != null ? existing.id() : null,
                sessionId,
                agentRole,
                status,
                autonomy,
                stateJson,
                existing != null ? existing.channelPlatformId() : null,
                existing != null ? existing.channelType() : null,
                goal,
                null,
                null,
                null,
                null,
                batchDecisions,
                null,
                null,
                null,
                iterationsUsed,
                null,
                null,
                contextDataJson,
                null,
                null,
                null,
                existing != null ? existing.parentCheckpointId() : null,
                existing != null ? existing.depth() : 0,
                existing != null ? existing.createdAt() : now,
                now,
                existing != null && existing.version() != null ? existing.version() : 0);
    }

    CheckpointDto buildFinalizedCheckpoint(
            CheckpointDto existing,
            String sessionId,
            String agentRole,
            String stateJson,
            String finalStatus,
            String originalGoal,
            String contextDataJson) {
        Instant now = Instant.now();
        Instant completedAt = CheckpointStatus.COMPLETED.name().equals(finalStatus) ? now : null;
        Instant failedAt = CheckpointStatus.FAILED.name().equals(finalStatus) ? now : null;
        String goal = (originalGoal != null && !originalGoal.isBlank())
                ? originalGoal
                : (existing != null ? existing.originalGoal() : null);
        return new CheckpointDto(
                existing != null ? existing.id() : null,
                sessionId,
                agentRole,
                finalStatus,
                existing != null ? existing.autonomyLevel() : null,
                stateJson,
                existing != null ? existing.channelPlatformId() : null,
                existing != null ? existing.channelType() : null,
                goal,
                failedAt,
                completedAt,
                null,
                null,
                null,
                null,
                null,
                existing != null ? existing.maxIterations() : null,
                existing != null ? existing.iterationsUsed() : 0,
                null,
                null,
                contextDataJson,
                null,
                null,
                null,
                existing != null ? existing.parentCheckpointId() : null,
                existing != null ? existing.depth() : 0,
                existing != null ? existing.createdAt() : now,
                now,
                existing != null && existing.version() != null ? existing.version() : 0);
    }

    Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
        if (RESERVED_DIRS.contains(sessionId)) return Optional.empty();
        Optional<CheckpointDto> direct =
                store.readOptional(List.of(DOMAIN, sessionId, sanitizeRole(agentRole) + ".json"), CheckpointDto.class);
        if (direct.isPresent()) return direct.map(this::resolveAuthoritativeCheckpoint);
        return loadAll().stream()
                .filter(checkpoint -> Objects.equals(sessionId, checkpoint.sessionId()))
                .filter(checkpoint -> Objects.equals(agentRole, checkpoint.agentRole()))
                .findFirst();
    }

    static String sanitizeRole(String role) {
        return role.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static Optional<String> updateJsonMapIfPending(String json, String key, String value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> map = json == null || json.isBlank() || json.equals("{}")
                    ? new LinkedHashMap<>()
                    : mapper.readValue(json, new TypeReference<Map<String, String>>() {});
            if (!"PENDING".equals(map.get(key))) return Optional.empty();
            map.put(key, value);
            return Optional.of(mapper.writeValueAsString(map));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
