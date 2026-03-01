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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

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

    @ConfigProperty(name = "arcclaw.sandbox.tmpfs-path", defaultValue = "/run/arcclaw/secrets")
    String tmpfsPath;

    private static final String CONTAINER_PIDS_LIMIT = "64";

    // Circuit breaker state
    public enum CircuitBreakerState { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
    private final AtomicInteger podmanFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0L);

    @ConfigProperty(name = "arcclaw.sandbox.podman.failure-threshold", defaultValue = "3")
    int podmanFailureThreshold;

    @ConfigProperty(name = "arcclaw.sandbox.podman.reset-timeout-seconds", defaultValue = "30")
    int resetTimeoutSeconds;

    public int getPodmanFailureThreshold() {
        return podmanFailureThreshold;
    }

    public int getResetTimeoutSeconds() {
        return resetTimeoutSeconds;
    }

    /**
     * Fix G2: Tracks names of all currently-running Podman containers.
     * Each container is started with --name zc-<sessionId>-<nonce>.
     * Name is added before podman run, removed in finally after it exits.
     *
     * @PreDestroy drains this set on JVM SIGTERM — stops any survivors.
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
                    openCircuit();
                }
            } catch (Exception e) {
                log.warn("Podman not found on PATH — isolation degraded to NAMESPACE strategy", e);
                openCircuit();
            }
        }
    }

    private void openCircuit() {
        state.set(CircuitBreakerState.OPEN);
        openedAt.set(System.currentTimeMillis());
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
            String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets) throws Exception {

        if ("NONE".equalsIgnoreCase(sandboxStrategy)) {
            return executeDirectly(workspaceRoot, command);
        }

        if ("PODMAN".equalsIgnoreCase(sandboxStrategy)) {
            CircuitBreakerState currentState = state.get();
            
            // Handle HALF_OPEN transition
            if (currentState == CircuitBreakerState.OPEN) {
                long elapsed = System.currentTimeMillis() - openedAt.get();
                if (elapsed >= TimeUnit.SECONDS.toMillis(resetTimeoutSeconds)) {
                    log.infof("Podman circuit breaker reset timeout reached. Transitioning to HALF_OPEN.");
                    state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
                    currentState = state.get();
                }
            }

            if (currentState != CircuitBreakerState.OPEN) {
                try {
                    String result = executeInContainer(sessionId, workspaceRoot, command, requiredSecrets);
                    
                    // Success! 
                    if (state.get() == CircuitBreakerState.HALF_OPEN) {
                        log.infof("Podman successful in HALF_OPEN state. Closing circuit breaker.");
                    }
                    state.set(CircuitBreakerState.CLOSED);
                    podmanFailures.set(0);
                    return result;
                } catch (ToolExecutionException e) {
                    // THE FIX: The tool ran but failed. Do NOT fall back. 
                    // Throw immediately so the LLM sees the tool's error output.
                    throw e;
                } catch (Exception e) {
                    int failures = podmanFailures.incrementAndGet();
                    log.errorf("Podman execution failed (session=%s, failureCount=%d, state=%s): %s",
                            sessionId, failures, state.get(), e.getMessage());

                    if (state.get() == CircuitBreakerState.HALF_OPEN || failures >= podmanFailureThreshold) {
                        log.errorf("Podman failed. Opening circuit breaker (state was %s).", state.get());
                        openCircuit();
                    }
                }
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
        
        // Use the unified Tier 1 tmpfs secure transport.
        // The transport strictly zeroes the memory arrays when closed.
        try (var transport = new TmpfsSecretTransport(sessionId, tmpfsPath)) {
            List<String> transportArgs = transport.prepare(resolvedSecrets);

        // Fix G2: deterministic container name for tracking + graceful stop on shutdown.
        // Format: zc-<sessionId truncated to 8 chars>-<nonce> to stay within 64-char limit.
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
                    "--pull", "never",
                    "--network=bridge",
                    "--read-only",
                    "--security-opt=no-new-privileges",
                    "--cap-drop=ALL",
                    "-v", workspaceRoot.toAbsolutePath() + ":/workspace:ro",
                    "--user=65534:65534",
                    "--memory=" + containerMemoryLimit, // Fix C2: OOM kill before host impact
                    "--cpus=" + containerCpuLimit,      // Fix C2: prevent CPU saturation
                    "--pids-limit=" + CONTAINER_PIDS_LIMIT, // Fix C2: block fork bombs
                    "--ulimit=nofile=256:256"            // Fix C2: limit open file descriptors
            ));
            
            // Inject the transport args (e.g. --env-file /run/arcclaw/secrets/<nonce>.env)
            podmanCmd.addAll(transportArgs);
            
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
            transport.deliver(process);
            var output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new ToolExecutionException("Tool timed out after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            
            if (exitCode != 0) {
                // Intercept the "Image Not Found" case
                if (output.contains("not known") || output.contains("not found")) {
                    log.warn("**====================================================================**");
                    log.warnf("[SANDBOX-WARNING] Sandbox image '%s' is missing from local storage.", containerImage);
                    log.warn("To use the secure Podman sandbox, please build the runner image:");
                    log.warnf("  podman build -t arcclaw-tool-runner -f ./sandbox/Dockerfile.toolrunner . ", containerImage);
                    log.warn("Falling back to host execution...");
                    log.warn("**====================================================================**");
                    
                    // Throw IOException to trigger the Graceful Degradation circuit breaker
                    throw new IOException("Missing sandbox image: " + containerImage);
                }
                
                // Handle other infrastructure failures (exit 125)
                if (exitCode == 125) {
                    throw new IOException("Podman engine failure (exit 125): " + output);
                }

                throw new ToolExecutionException("Tool failed (exit " + exitCode + "): " + output);
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
        }
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
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
        }
        if (process.exitValue() != 0) {
            throw new ToolExecutionException("Tool failed (exit " + process.exitValue() + "): " + output);
        }
        return output;
    }

    /** Dev mode: no isolation, direct execution. */
    private String executeDirectly(Path workspaceRoot, List<String> command) throws Exception {
        var pb = new ProcessBuilder(command);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        var output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
        }
        if (process.exitValue() != 0) {
            throw new ToolExecutionException("Tool failed (exit " + process.exitValue() + "): " + output);
        }
        return output;
    }

    /**
     * Resolves vault secrets for container injection as raw byte arrays.
     * <p>
     * Fail-closed contract:
     * - null map     → IllegalArgumentException (programming error in caller)
     * - empty map    → empty result (valid — tool requires no secrets)
     * - missing key  → SecurityException (vault must be the sole source of truth)
     * <p>
     * The returned byte arrays are owned by this method's callers and MUST be
     * zeroed after use. {@link #writeEphemeralEnvFile} zeros them in its finally block.
     */
    private Map<String, byte[]> resolveSecrets(Map<String, String> requiredSecrets) {
        if (requiredSecrets == null) {
            throw new IllegalArgumentException(
                    "requiredSecrets must not be null — check ToolRegistry.extractRequiredSecrets()");
        }
        if (requiredSecrets.isEmpty()) {
            return new LinkedHashMap<>();
        }

        var resolved = new LinkedHashMap<String, byte[]>();
        for (var entry : requiredSecrets.entrySet()) {
            String envVar = entry.getKey();
            String vaultKeyName = entry.getValue();

            var optionalKey = secretVault.get(vaultKeyName);

            if (optionalKey.isPresent()) {
                try (VaultKey key = optionalKey.get()) {
                    // SECURE TRANSFER: direct byte array copy — no String materialization.
                    // The VaultKey source bytes are zeroed on close() above.
                    byte[] src = key.get();
                    byte[] copy = new byte[src.length];
                    System.arraycopy(src, 0, copy, 0, src.length);
                    resolved.put(envVar, copy);
                }
            } else {
                // FAIL-CLOSED: no hardcoded substitutions, no System.getenv() fallback.
                throw new SecurityException("Required secret not in vault: " + vaultKeyName);
            }
        }
        return resolved;
    }
    public static class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String message) { super(message); }
    }
}
