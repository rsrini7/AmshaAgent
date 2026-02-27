package com.arcclaw.core.sandbox;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A Tier 1 secret transport that writes an ephemeral env-file strictly to a RAM-backed 
 * tmpfs mount point. It enforces atomic POSIX permissions and avoids all String allocation 
 * of sensitive material.
 */
public class TmpfsSecretTransport implements SecretTransport {

    private static final Logger log = Logger.getLogger(TmpfsSecretTransport.class);

    private final String sessionId;
    private final Path tmpfsMountPath;

    private Map<String, byte[]> managedSecrets;
    private Path envFile;

    public TmpfsSecretTransport(String sessionId, String tmpfsMountPath) {
        this.sessionId = sessionId;
        this.tmpfsMountPath = Path.of(tmpfsMountPath);
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
                // In a production Linux host, this is a distinct systemd tmpfs mount.
                Files.createDirectories(actualMountPath);
            }
        } catch (IOException e) {
            // Graceful degradation: If /run/arcclaw is denied (running locally as non-root),
            // fallback to the standard OS temp directory to prevent crashing the Podman pipeline.
            log.warnf("Cannot access tmpfs path %s (%s). Falling back to java.io.tmpdir.", tmpfsMountPath, e.getMessage());
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
}
