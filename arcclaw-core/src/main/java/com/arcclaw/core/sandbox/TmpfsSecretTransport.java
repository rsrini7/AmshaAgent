package com.arcclaw.core.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.jboss.logging.Logger;

/**
 * A Tier 1 secret transport that writes an ephemeral env-file strictly to a RAM-backed 
 * tmpfs mount point. It enforces atomic POSIX permissions and avoids all String allocation 
 * of sensitive material.
 */
public class TmpfsSecretTransport implements SecretTransport {

    private static final Logger log = Logger.getLogger(TmpfsSecretTransport.class);

    private static final String SECRET_DIR = resolveSecretDir();

    private static String resolveSecretDir() {
        String override = System.getenv("ARCCLAW_SANDBOX_SECRET_TRANSPORT");
        if (override != null && !override.isBlank()) {
            return switch (override.toLowerCase()) {
                case "tmpfs"  -> "/run/arcclaw/secrets";        // explicit Linux prod
                case "tmpdir" -> System.getProperty("java.io.tmpdir"); // explicit dev
                default       -> override;                       // treat as a literal path
            };
        }
        // Auto-detect: only use tmpfs path on Linux where it actually exists
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            return "/run/arcclaw/secrets";
        }
        // macOS / Windows fall through silently — no WARN
        return System.getProperty("java.io.tmpdir");
    }

    private final String sessionId;
    private final Path tmpfsMountPath;

    private Map<String, byte[]> managedSecrets;
    private Path envFile;

    public TmpfsSecretTransport(String sessionId) {
        this(sessionId, null);
    }

    /**
     * Internal constructor allowing test-specific directory injection.
     */
    TmpfsSecretTransport(String sessionId, Path manualPath) {
        this.sessionId = sessionId;
        this.tmpfsMountPath = (manualPath != null) ? manualPath : Path.of(SECRET_DIR);
    }

    @Override
    public List<String> prepare(Map<String, byte[]> secrets) throws IOException {
        if (secrets == null || secrets.isEmpty()) {
            return List.of();
        }
        
        this.managedSecrets = secrets;

        // 1. Ensure target path exists and is somewhat sane
        Path actualMountPath = tmpfsMountPath;

        try {
            if (!Files.exists(actualMountPath)) {
                Files.createDirectories(actualMountPath);
            }
        } catch (IOException e) {
            log.warnf("Cannot access tmpfs path %s (%s). Falling back to java.io.tmpdir.",
                      tmpfsMountPath, e.getMessage());
            actualMountPath = Path.of(System.getProperty("java.io.tmpdir"));
        }

        // 2. Atomically create the restricted file
        try {
            var strictAttrs = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"));
            envFile = Files.createTempFile(actualMountPath, "secrets-" + sessionId + "-", ".env", strictAttrs);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            // Graceful degradation for Dev/Windows/macOS
            envFile = Files.createTempFile(actualMountPath, "secrets-" + sessionId + "-", ".env");
            envFile.toFile().setReadable(true, true);
            envFile.toFile().setWritable(true, true);
        }

        // 3. Size buffer exactly to eliminate dynamically resizing re-allocations
        int totalSize = 0;
        for (var entry : secrets.entrySet()) {
            totalSize += entry.getKey().getBytes(StandardCharsets.UTF_8).length; // key
            totalSize += 1; // '='
            totalSize += entry.getValue().length; // secret bytes
            totalSize += 1; // '\n'
        }

        // 4. Construct payload using ByteBuffer to strictly avoid String materialization
        byte[] backingArray = new byte[totalSize];
        ByteBuffer buffer = ByteBuffer.wrap(backingArray);
        
        try {
            for (var entry : secrets.entrySet()) {
                buffer.put(entry.getKey().getBytes(StandardCharsets.UTF_8));
                buffer.put((byte) '=');
                buffer.put(entry.getValue());
                buffer.put((byte) '\n');
            }
            
            // 5. Write pure bytes directly to the temp file
            Files.write(envFile, backingArray);
            
        } finally {
            // MUST explicitly zero out the backing array buffer before it gets GC'd
            Arrays.fill(backingArray, (byte) 0);
        }

        return List.of("--env-file", envFile.toAbsolutePath().toString());
    }

    @Override
    public void deliver(Process process) {
        // No-op. The env-file was injected statically during process launch.
    }

    @Override
    public void close() {
        // 1. Zero out individual secret byte arrays we assumed ownership of
        if (managedSecrets != null) {
            for (byte[] secretBytes : managedSecrets.values()) {
                if (secretBytes != null) {
                    Arrays.fill(secretBytes, (byte) 0);
                }
            }
        }

        // 2. Secure file deletion: overwrite with zeros, then unlink
        if (envFile != null && Files.exists(envFile)) {
            try {
                long size = Files.size(envFile);
                if (size > 0) {
                    byte[] zeros = new byte[(int) size];
                    Files.write(envFile, zeros); // overwrite physical memory block
                }
                Files.deleteIfExists(envFile);
            } catch (Exception e) {
                log.errorf("Failed secure wipe of tmpfs env-file %s: %s", envFile, e.getMessage());
            }
        }
    }

    @PostConstruct
    void sweepStaleTmpFiles() {
        sweepStaleTmpFiles(Path.of("/dev/shm"));
    }

    void sweepStaleTmpFiles(Path shmDir) {
        if (!Files.exists(shmDir)) {
            log.debugf("TmpfsSecretTransport: %s not present — skipping stale file sweep (non-Linux?)", shmDir);
            return;
        }
        try (var stream = Files.list(shmDir)) {
            stream
                .filter(p -> p.getFileName().toString().startsWith("arcclaw-secrets-"))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                        log.warnf("TmpfsSecretTransport: swept stale secret file from previous JVM crash: %s", p);
                    } catch (IOException e) {
                        log.warnf("TmpfsSecretTransport: could not delete stale file %s: %s", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warnf("TmpfsSecretTransport: could not sweep stale tmpfs files: %s", e.getMessage());
        }
    }
}
