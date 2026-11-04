package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.amsha.persistence.api.ActionClaimStatus;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * File I/O, locking, atomic write, and crash recovery helper for filesystem tool attempt storage
 * per Proposal §12.4.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsToolAttemptHelper {

    static final String DOMAIN = "tool_attempts";
    static final String ATTEMPTS_DIR = "attempts";
    static final String CLAIMS_FILE = "claims.json";
    static final String LOCK_FILE = ".workspace.lock";

    private final Path rootDir;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    @Inject
    public FsToolAttemptHelper(FilesystemFileStore fileStore) {
        this.rootDir = fileStore.resolve(List.of(DOMAIN));
        this.mapper = fileStore.objectMapper();
    }

    public FsToolAttemptHelper(Path baseDataDir, ObjectMapper mapper) {
        this.rootDir = baseDataDir.resolve(DOMAIN).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    protected FsToolAttemptHelper() {
        this.rootDir = Path.of("./data/fs").resolve(DOMAIN).toAbsolutePath().normalize();
        this.mapper = new ObjectMapper();
    }

    public <T> T withWorkspaceLock(String workspaceId, Callable<T> action) {
        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(workspaceId, k -> new ReentrantLock());
        jvmLock.lock();
        try {
            Path lockPath = workspacePath(workspaceId).resolve(LOCK_FILE);
            try {
                Files.createDirectories(lockPath.getParent());
                try (FileChannel channel =
                                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        FileLock fileLock = channel.lock()) {
                    return action.call();
                }
            } catch (Exception e) {
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    public void repairOrRecoverClaims(String workspaceId) {
        FsWorkspaceClaimsEnvelope claimsEnv = loadClaimsEnvelope(workspaceId);
        List<ToolActionClaimDto> repaired = new ArrayList<>();
        boolean modified = false;

        for (ToolActionClaimDto c : claimsEnv.claims()) {
            Optional<FsToolAttemptEnvelope> attOpt = loadAttemptEnvelope(workspaceId, c.attemptId());
            if (c.status() == ActionClaimStatus.RESERVED) {
                if (attOpt.isEmpty()) {
                    modified = true;
                    continue; // Absence of attempt releases dangling claim (§12.4)
                }
                ToolAttemptDto att = attOpt.get().attempt();
                if (att.status() == ToolAttemptStatus.SETTLED_SUCCESS
                        || att.status() == ToolAttemptStatus.SETTLED_ERROR
                        || att.status() == ToolAttemptStatus.TIMED_OUT) {
                    repaired.add(new ToolActionClaimDto(
                            c.workspaceId(),
                            c.sessionId(),
                            c.toolName(),
                            c.fingerprintVersion(),
                            c.actionFingerprint(),
                            ActionClaimStatus.CONSUMED,
                            c.operationId(),
                            c.attemptId(),
                            c.createdAt(),
                            att.settledAt()));
                    modified = true;
                } else if (att.status() == ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH) {
                    modified = true; // cancellation releases claim
                } else {
                    repaired.add(c);
                }
            } else {
                repaired.add(c);
            }
        }

        if (modified) {
            saveClaimsEnvelope(new FsWorkspaceClaimsEnvelope(claimsEnv.version(), workspaceId, repaired));
        }

        Set<String> reservedClaimAttemptIds = repaired.stream()
                .filter(c -> c.status() == ActionClaimStatus.RESERVED)
                .map(ToolActionClaimDto::attemptId)
                .collect(Collectors.toSet());

        for (FsToolAttemptEnvelope env : loadAllEnvelopes(workspaceId)) {
            ToolAttemptDto att = env.attempt();
            if (att.idempotencyModeSnapshot() == ToolAttemptIdempotencyMode.AT_MOST_ONCE
                    && (att.status() == ToolAttemptStatus.LAUNCH_REQUESTED || att.status() == ToolAttemptStatus.RUNNING)
                    && !reservedClaimAttemptIds.contains(att.attemptId())) {
                throw new IllegalStateException(
                        "AT_MOST_ONCE attempt in " + att.status() + " missing required RESERVED claim");
            }
        }
    }

    public FsWorkspaceClaimsEnvelope loadClaimsEnvelope(String workspaceId) {
        Path p = claimsFilePath(workspaceId);
        if (!Files.exists(p)) return FsWorkspaceClaimsEnvelope.empty(workspaceId);
        try {
            return mapper.readValue(p.toFile(), FsWorkspaceClaimsEnvelope.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read claims envelope: " + p, e);
        }
    }

    public void saveClaimsEnvelope(FsWorkspaceClaimsEnvelope env) {
        writeAtomicallyWithFsync(claimsFilePath(env.workspaceId()), env);
    }

    public Optional<FsToolAttemptEnvelope> loadAttemptEnvelope(String workspaceId, String attemptId) {
        Path p = attemptFilePath(workspaceId, attemptId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(p.toFile(), FsToolAttemptEnvelope.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read attempt envelope: " + p, e);
        }
    }

    public void saveAttemptEnvelope(FsToolAttemptEnvelope env) {
        writeAtomicallyWithFsync(
                attemptFilePath(env.attempt().workspaceId(), env.attempt().attemptId()), env);
    }

    public void deleteAttemptFile(String workspaceId, String attemptId) {
        try {
            Files.deleteIfExists(attemptFilePath(workspaceId, attemptId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<FsToolAttemptEnvelope> loadAllEnvelopes(String workspaceId) {
        Path dir = attemptsDir(workspaceId);
        if (!Files.exists(dir)) return List.of();
        List<FsToolAttemptEnvelope> list = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    list.add(mapper.readValue(p.toFile(), FsToolAttemptEnvelope.class));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read attempt: " + p, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return list;
    }

    public Set<String> listWorkspaceIds() {
        if (!Files.exists(rootDir)) return Set.of();
        try (var s = Files.list(rootDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> {
                        try {
                            return decodeWorkspaceId(p.getFileName().toString());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeAtomicallyWithFsync(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dirChannel = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                dirChannel.force(true);
            } catch (Exception _) {
                // directory fsync is best-effort across platforms
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write atomically: " + target, e);
        }
    }

    public Path workspacePath(String workspaceId) {
        return rootDir.resolve(encodeWorkspaceId(workspaceId));
    }

    public Path attemptsDir(String workspaceId) {
        return workspacePath(workspaceId).resolve(ATTEMPTS_DIR);
    }

    public Path attemptFilePath(String workspaceId, String attemptId) {
        return attemptsDir(workspaceId).resolve(encodeAttemptId(attemptId) + ".json");
    }

    public Path claimsFilePath(String workspaceId) {
        return workspacePath(workspaceId).resolve(CLAIMS_FILE);
    }

    public static String encodeWorkspaceId(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(workspaceId.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeWorkspaceId(String encoded) {
        Objects.requireNonNull(encoded, "encoded must not be null");
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    public static String encodeAttemptId(String attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(attemptId.getBytes(StandardCharsets.UTF_8));
    }
}
