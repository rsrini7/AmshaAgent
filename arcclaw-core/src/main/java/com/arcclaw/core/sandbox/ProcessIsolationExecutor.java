package com.arcclaw.core.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.sandbox.SandboxCircuitBreaker.CircuitBreakerState;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
    SandboxCircuitBreaker circuitBreaker;

    @Inject
    PodmanContainerExecutor podmanExecutor;

    @Inject
    NamespaceExecutor namespaceExecutor;

    @Inject
    DirectExecutor directExecutor;

    @ConfigProperty(name = "arcclaw.sandbox.strategy", defaultValue = "PODMAN")
    String sandboxStrategy;

    @ConfigProperty(name = "arcclaw.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    public int getPodmanFailureThreshold() {
        return circuitBreaker.getPodmanFailureThreshold();
    }

    public int getResetTimeoutSeconds() {
        return circuitBreaker.getResetTimeoutSeconds();
    }

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
                    circuitBreaker.openCircuit();
                }
            } catch (Exception e) {
                log.warn("Podman not found on PATH — isolation degraded to NAMESPACE strategy", e);
                circuitBreaker.openCircuit();
            }
        }
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

        // arcclaw.sandbox.enabled=false overrides strategy entirely.
        // Tools run directly on host PATH regardless of configured strategy.
        // Set ARCCLAW_SANDBOX_ENABLED=true in .env to restore isolation.
        if (!sandboxEnabled) {
            log.debugf("Sandbox disabled — executing directly (session=%s)", sessionId);
            return directExecutor.executeDirectly(workspaceRoot, command);
        }

        if ("NONE".equalsIgnoreCase(sandboxStrategy)) {
            return directExecutor.executeDirectly(workspaceRoot, command);
        }

        if ("PODMAN".equalsIgnoreCase(sandboxStrategy)) {
            if (circuitBreaker.shouldAttemptPodman()) {
                CircuitBreakerState stateAtCall = circuitBreaker.getState();
                try {
                    String result = podmanExecutor.executeInContainer(
                            sessionId, workspaceRoot, command, requiredSecrets);
                    circuitBreaker.recordSuccess();
                    return result;
                } catch (ToolExecutionException e) {
                    // Tool ran but failed — do NOT fall back to namespace
                    throw e;
                } catch (Exception e) {
                    log.errorf("Podman execution failed (session=%s): %s", sessionId, e.getMessage());
                    circuitBreaker.recordFailure(stateAtCall);
                }
            }
        }
        return namespaceExecutor.executeInNamespace(workspaceRoot, command);
    }
}
