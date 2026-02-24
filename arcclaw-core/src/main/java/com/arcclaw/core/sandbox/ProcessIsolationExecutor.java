package com.arcclaw.core.sandbox;

import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OS-level process isolation for tool execution.
 * <p>
 * Strategy A (recommended): Podman rootless container per tool call.
 * Strategy B (lightweight): Linux unshare namespaces via ProcessBuilder.
 * Strategy NONE: dev mode — direct execution, no isolation.
 * <p>
 * Resource caps (Fix C2): --memory, --cpus, --pids-limit, --ulimit.
 * Circuit breaker (Fix C3): auto-fallback to NAMESPACE after 3 Podman failures.
 * <li><strong>Security</strong>:
 * Secret injection (V1.0.0): ephemeral env-file, deleted in finally, never in process args.
 * OTel tracing: child spans attached to session root span.
 * Fix G2: @PreDestroy graceful container shutdown.
 */
@ApplicationScoped
public class ProcessIsolationExecutor {

    private static final Logger log = Logger.getLogger(ProcessIsolationExecutor.class);

    @Inject
    SecretVault secretVault;

    @Inject
    AgentOtelContext otelContext;

    @ConfigProperty(name = "arcclaw.sandbox.strategy", defaultValue = "PODMAN")
    String sandboxStrategy;

    @ConfigProperty(name = "arcclaw.sandbox.container-image", defaultValue = "arcclaw-tool-runner:latest")
    String containerImage;

    @ConfigProperty(name = "arcclaw.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @ConfigProperty(name = "arcclaw.sandbox.memory-limit", defaultValue = "512m")
    String containerMemoryLimit;

    @ConfigProperty(name = "arcclaw.sandbox.cpu-limit", defaultValue = "0.5")
    String containerCpuLimit;

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    private static final String CONTAINER_PIDS_LIMIT = "64";

    // Circuit breaker state
    private final AtomicInteger podmanFailures = new AtomicInteger(0);
    private static final int PODMAN_FAILURE_THRESHOLD = 3;
    private volatile boolean podmanCircuitOpen = false;

    /**
     * Fix G2: Tracks names of all currently-running Podman containers.
     * Each container is started with --name zc-<sessionId>-<nonce>.
     * Name is added before podman run, removed in finally after it exits.
     * * @PreDestroy drains this set on JVM SIGTERM — stops any survivors.
     */
    private final Set<String> activeContainerNames = ConcurrentHashMap.newKeySet();

    /** Startup availability check — degrades gracefully if Podman not installed. */
    @PostConstruct
    void checkPodmanAvailability() {
        if ("PODMAN".equalsIgnoreCase(sandboxStrategy)) {
            try {
                var pb = new ProcessBuilder(podmanPath, "--version");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                boolean ok = proc.waitFor(5, TimeUnit.SECONDS);
                if (!ok || proc.exitValue() != 0) {
                    log.warn("Podman not available — circuit open, falling back to NAMESPACE strategy.");
                    podmanCircuitOpen = true;
                }
            } catch (Exception e) {
                log.warn("Podman not found on PATH — isolation degraded to NAMESPACE strategy", e);
                podmanCircuitOpen = true;
            }
        }
    }

    /**
     * Fix G2: Graceful container shutdown on JVM SIGTERM / @ApplicationScoped destroy.
     * <p>
     * Stops all tracked containers with a 10-second grace period
     * (matching Kubernetes terminationGracePeriodSeconds default).
     */
    @PreDestroy
    void stopAllContainersOnShutdown() {
        if (activeContainerNames.isEmpty()) return;
        
        log.infof("JVM shutdown: stopping %d active Podman container(s)...", activeContainerNames.size());
        for (String containerName : activeContainerNames) {
            try {
                var pb = new ProcessBuilder(podmanPath, "stop", "--time", "10", containerName);
                pb.redirectErrorStream(true);
                var proc = pb.start();
                boolean stopped = proc.waitFor(15, TimeUnit.SECONDS); // 10s grace + 5s buffer
                if (stopped && proc.exitValue() == 0) {
                    log.infof("Container %s stopped cleanly.", containerName);
                } else {
                    log.warnf("Container %s did not stop within grace period (exit=%d).",
                            containerName, stopped ? proc.exitValue() : -1);
                }
            } catch (Exception e) {
                log.warnf("Failed to stop container %s: %s", containerName, e.getMessage());
            }
        }
        activeContainerNames.clear();
    }

    /**
     * Primary entry point — routes to Podman, NAMESPACE, or NONE based on strategy + circuit state.
     *
     * @param sessionId       The workspace session ID.
     * @param workspaceRoot   The workspace root directory.
     * @param command         The command to execute.
     * @param requiredSecrets Map of envVar → vaultKey for secrets to inject.
     * @return The command output (stdout + stderr).
     */
    public String executeSandboxed(
            String sessionId,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets) throws Exception {

        if ("NONE".equalsIgnoreCase(sandboxStrategy)) {
            return executeDirectly(workspaceRoot, command);
        }

        if ("PODMAN".equalsIgnoreCase(sandboxStrategy) && !podmanCircuitOpen) {
            try {
                String result = executeInContainer(sessionId, workspaceRoot, command, requiredSecrets);
                podmanFailures.set(0);
                return result;
            } catch (Exception e) {
                int failures = podmanFailures.incrementAndGet();
                log.errorf("Podman execution failed (session=%s, failureCount=%d): %s",
                        sessionId, failures, e.getMessage());

                if (failures >= PODMAN_FAILURE_THRESHOLD) {
                    log.errorf("Podman failed %d times — opening circuit breaker, falling back to NAMESPACE", failures);
                    podmanCircuitOpen = true;
                }
                // Fall through to NAMESPACE
            }
        }

        return executeInNamespace(workspaceRoot, command);
    }

    /**
     * Strategy A: Podman rootless container with resource caps + ephemeral secret injection.
     * Fix G2: container is named and tracked for graceful @PreDestroy shutdown.
     */
    private String executeInContainer(
            String sessionId,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets) throws Exception {

        var resolvedSecrets = resolveSecrets(requiredSecrets);
        var envFile = writeEphemeralEnvFile(sessionId, resolvedSecrets);

        // Fix G2: deterministic container name for tracking + graceful stop on shutdown.
        String containerName = "zc-" + sessionId.substring(0, Math.min(8, sessionId.length()))
                + "-" + Long.toHexString(System.nanoTime());

        Span span = otelContext.childSpan(sessionId, "tool.container_exec");
        span.setAttribute("container.image", containerImage);
        span.setAttribute("container.name", containerName);
        span.setAttribute("sandbox.strategy", "PODMAN");

        activeContainerNames.add(containerName); // track before start — avoids race on early failure
        try (Scope scope = span.makeCurrent()) {
            var podmanCmd = new ArrayList<>(List.of(
                    podmanPath, "run", "--rm",
                    "--name", containerName, // Fix G2: named for tracking + graceful stop
                    "--network=bridge",
                    "--read-only",
                    "--security-opt=no-new-privileges",
                    "--cap-drop=ALL",
                    "-v", workspaceRoot.toAbsolutePath() + ":/workspace:ro",
                    "--user=65534:65534",
                    "--memory=" + containerMemoryLimit, // Fix C2: OOM kill before host impact
                    "--cpus=" + containerCpuLimit, // Fix C2: prevent CPU saturation
                    "--pids-limit=" + CONTAINER_PIDS_LIMIT, // Fix C2: block fork bombs
                    "--ulimit=nofile=256:256", // Fix C2: limit open file descriptors
                    "--env-file", envFile.toString() // secrets via env-file, not process args
            ));
            podmanCmd.add(containerImage);
            podmanCmd.addAll(command);

            var pb = new ProcessBuilder(podmanCmd);

            Path workspaceDir = Path.of(workspaceRootDir, sessionId);
            if (!Files.exists(workspaceDir)) {
                throw new IOException("workspace dir not exist");
            }
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);

            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Tool failed (exit " + process.exitValue() + "): " + output);
            }
            span.setStatus(StatusCode.OK);
            return output;

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
            activeContainerNames.remove(containerName); // Fix G2: deregister on normal exit
            deleteEphemeralEnvFile(envFile);
            // Notice: clearSecretsFromMemory is no longer needed here because 
            // writeEphemeralEnvFile wipes the byte arrays directly in its finally block.
        }
    }

    /**
     * Strategy B: Linux unshare namespaces (lighter, no Podman needed).
     * Falls back gracefully on non-Linux (dev/macOS/Windows).
     */
    private String executeInNamespace(Path workspaceRoot, List<String> command) throws Exception {
        boolean isLinux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        ProcessBuilder pb;
        if (isLinux) {
            var fullCmd = new ArrayList<>(List.of(
                    "unshare", "--pid", "--fork", "--mount", "--net", "--map-root-user",
                    "chroot", workspaceRoot.toAbsolutePath().toString()));
            fullCmd.addAll(command);
            pb = new ProcessBuilder(fullCmd);
        } else {
            pb = new ProcessBuilder(command);
            pb.directory(workspaceRoot.toFile());
        }
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("Tool failed (exit " + proc.exitValue() + "): " + out);
        }
        return out;
    }

    /** Dev mode: no isolation, direct execution. */
    private String executeDirectly(Path workspaceRoot, List<String> command) throws Exception {
        var pb = new ProcessBuilder(command);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("Tool failed (exit " + proc.exitValue() + "): " + out);
        }
        return out;
    }

    /**
     * Resolves vault secrets for container injection safely using byte arrays.
     * <p>
     * The returned byte arrays will be securely zeroed out inside 
     * {@link #writeEphemeralEnvFile(String, Map)}.
     */
    private Map<String, byte[]> resolveSecrets(Map<String, String> requiredSecrets) {
        var resolved = new LinkedHashMap<String, byte[]>();
        
        if (requiredSecrets == null) {
            throw new IllegalArgumentException(
                "requiredSecrets must not be null — check ToolRegistry.extractRequiredSecrets()");
        }
        if (requiredSecrets.isEmpty()) {
            return resolved;
        }

        for (var entry : requiredSecrets.entrySet()) {
            String envVar = entry.getKey();
            String vaultKeyName = entry.getValue();
            
            var optionalKey = secretVault.get(vaultKeyName);
            
            if (optionalKey.isPresent()) {
                try (VaultKey key = optionalKey.get()) {
                    // SECURE TRANSFER: Direct byte array copy, absolutely NO Strings.
                    byte[] secretBytes = key.get();
                    byte[] copy = new byte[secretBytes.length];
                    System.arraycopy(secretBytes, 0, copy, 0, secretBytes.length);
                    resolved.put(envVar, copy);
                }
            } else {
                // FAIL-CLOSED: No hardcoded substitutions, no environment variable leaks.
                throw new SecurityException("Required secret not in vault: " + vaultKeyName);
            }
        }
        return resolved;
    }

    /**
     * Creates the file, securely writes the byte payload, and aggressively zeros memory.
     */
    private Path writeEphemeralEnvFile(String sessionId, Map<String, byte[]> secrets) throws IOException {
        Path workspaceDir = Path.of(workspaceRootDir, sessionId);
        if (!Files.exists(workspaceDir)) {
            Files.createDirectories(workspaceDir);
        }
        
        Path envFile = Files.createTempFile(workspaceDir, "secrets-", ".env");

        if (secrets == null || secrets.isEmpty()) {
            setStrictFilePermissions(envFile);
            return envFile;
        }

        byte[] payload = null;
        try (var baos = new ByteArrayOutputStream()) {
            for (var entry : secrets.entrySet()) {
                baos.write((entry.getKey() + "=").getBytes(StandardCharsets.UTF_8));
                baos.write(entry.getValue()); // Write raw bytes
                baos.write('\n');
            }
            
            payload = baos.toByteArray();
            
            Files.write(envFile, payload);
            setStrictFilePermissions(envFile);
            
            return envFile;

        } finally {
            // Memory Wipe: Zero the payload array
            if (payload != null) {
                Arrays.fill(payload, (byte) 0);
            }
            // Memory Wipe: Zero the individual secret arrays in the map
            for (byte[] secretBytes : secrets.values()) {
                if (secretBytes != null) {
                    Arrays.fill(secretBytes, (byte) 0);
                }
            }
        }
    }

    private void setStrictFilePermissions(Path envFile) throws IOException {
        try {
            Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            envFile.toFile().setReadable(true, true);
            envFile.toFile().setWritable(true, true);
        }
    }

    private void deleteEphemeralEnvFile(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (Exception ignored) {
        }
    }
}