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
 * Resource caps: --memory, --cpus, --pids-limit, --ulimit.
 * Circuit breaker: auto-fallback to NAMESPACE after N Podman transient failures.
 * <p>
 * <strong>Important:</strong> {@link SandboxImageMissingException} is NOT recorded
 * as a circuit-breaker failure — a missing image is a permanent configuration error,
 * not a transient Podman failure. Recording it would open the circuit and silently
 * degrade to namespace execution, which is less secure and equally broken until the
 * image is rebuilt. Callers receive {@link SandboxResult#infraFailure} instead.
 * <p>
 * <strong>Known limitation:</strong> {@code requiredSecrets} is NOT passed to
 * {@link NamespaceExecutor} on circuit-breaker fallback — namespace isolation does
 * not support the tmpfs secret transport used by Podman. Tool calls requiring secrets
 * will receive empty environment variables in namespace mode. This is a known
 * security degradation accepted as part of the fallback trade-off.
 */
@ApplicationScoped
public class ProcessIsolationExecutor {

    private static final Logger log = Logger.getLogger(ProcessIsolationExecutor.class);

    public static final String SANDBOX_STRATEGY_PODMAN = "PODMAN";

    @Inject
    SandboxCircuitBreaker circuitBreaker;

    @Inject
    PodmanContainerExecutor podmanExecutor;

    @Inject
    NamespaceExecutor namespaceExecutor;

    @Inject
    DirectExecutor directExecutor;

    @ConfigProperty(name = "arcclaw.sandbox.strategy", defaultValue = SANDBOX_STRATEGY_PODMAN)
    String sandboxStrategy;

    @ConfigProperty(name = "arcclaw.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "arcclaw.sandbox.allow-insecure-fallback", defaultValue = "false")
    boolean allowInsecureFallback;

    public int getPodmanFailureThreshold() {
        return circuitBreaker.getPodmanFailureThreshold();
    }

    public int getResetTimeoutSeconds() {
        return circuitBreaker.getResetTimeoutSeconds();
    }

    /** Startup availability check — degrades gracefully if Podman not installed. */
    @PostConstruct
    void checkPodmanAvailability() {
        if (SANDBOX_STRATEGY_PODMAN.equalsIgnoreCase(sandboxStrategy)) {
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
     * @return SandboxResult indicating success, failure, or infra failure.
     */
    public SandboxResult executeSandboxed(
            String sessionId,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets) throws Exception {

        if (!sandboxEnabled) {
            log.debugf("Sandbox disabled — executing directly (session=%s)", sessionId);
            return SandboxResult.success(directExecutor.executeDirectly(workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
        }

        if ("NONE".equalsIgnoreCase(sandboxStrategy)) {
            return SandboxResult.success(directExecutor.executeDirectly(workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
        }

        if (SANDBOX_STRATEGY_PODMAN.equalsIgnoreCase(sandboxStrategy)) {
            if (circuitBreaker.shouldAttemptPodman()) {
                CircuitBreakerState stateAtCall = circuitBreaker.getState();
                try {
                    String result = podmanExecutor.executeInContainer(
                            sessionId, workspaceRoot, command, requiredSecrets);
                    circuitBreaker.recordSuccess();
                    return SandboxResult.success(result);

                } catch (SandboxImageMissingException e) {
                    // FIX: do NOT record a circuit-breaker failure for a missing image.
                    //
                    // A missing image is a permanent configuration error — the image will
                    // not reappear until someone runs `podman build`. Recording it as a
                    // Podman failure opens the circuit and silently downgrades execution
                    // to namespace mode, which is less secure and equally broken for tools
                    // that need the runner image. The correct response is infraFailure so
                    // the agent surfaces the error to the user immediately.
                    log.errorf("[SANDBOX] Image missing (session=%s): %s", sessionId, e.getMessage());
                    if (allowInsecureFallback) {
                        log.warnf("[SANDBOX] allow-insecure-fallback enabled — degrading to host execution for session %s", sessionId);
                        return SandboxResult.success(namespaceExecutor.executeInNamespace(
                                workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
                    }
                    return SandboxResult.infraFailure(
                            "SANDBOX_OFFLINE: The sandbox image is missing. " +
                            "Please rebuild it: podman build --no-cache -t arcclaw-tool-runner " +
                            "-f ./sandbox/Dockerfile.toolrunner .");

                } catch (ToolExecutionException e) {
                    // Tool logic failure (non-zero exit from user code) — not an infra error.
                    // Re-throw so SandboxExecTool can return it as a normal tool failure.
                    throw e;

                } catch (Exception e) {
                    // Transient Podman infrastructure failure — record against the circuit.
                    // FIX: null-guard e.getMessage() — some exception types return null here.
                    String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.errorf("Podman execution failed (session=%s): %s", sessionId, errMsg);
                    circuitBreaker.recordFailure(stateAtCall);
                    if (allowInsecureFallback) {
                        log.warnf("[SANDBOX] allow-insecure-fallback enabled — degrading to host execution for session %s (Podman error: %s)", sessionId, errMsg);
                        return SandboxResult.success(namespaceExecutor.executeInNamespace(
                                workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
                    }
                    return SandboxResult.infraFailure(
                            "SANDBOX_OFFLINE: Podman is unavailable — " + errMsg);
                }
            }
        }

        // Podman circuit is open (or strategy is NAMESPACE) — fall back to namespace isolation.
        // NOTE: requiredSecrets are NOT injected in namespace mode (see class-level Javadoc).
        return SandboxResult.success(namespaceExecutor.executeInNamespace(workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
    }

    /**
     * Rewrites container-side /workspace paths to the actual host workspaceRoot.
     * Only needed for NONE/NAMESPACE strategies where execution is NOT inside a container.
     * Podman mounts workspaceRoot as /workspace itself, so no rewriting needed there.
     */
    private List<String> rewriteContainerPaths(List<String> command, Path workspaceRoot) {
        String hostPath = workspaceRoot.toAbsolutePath().toString()
                .replace("\\", "/");   // normalize Windows backslashes → forward slashes
        return command.stream()
                .map(arg -> arg.replace("/workspace", hostPath))
                .toList();
    }
}