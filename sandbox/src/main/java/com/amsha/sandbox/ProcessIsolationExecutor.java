package com.amsha.sandbox;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.sandbox.SandboxCircuitBreaker.CircuitBreakerState;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

@Startup
@ApplicationScoped
@Unremovable
public class ProcessIsolationExecutor implements SandboxExecutionPort {

    private static final Logger log = Logger.getLogger(ProcessIsolationExecutor.class);

    @Inject
    SandboxCircuitBreaker circuitBreaker;

    @Inject
    PodmanContainerExecutor podmanExecutor;

    @Inject
    NamespaceExecutor namespaceExecutor;

    @Inject
    DirectExecutor directExecutor;

    @Inject
    SandboxSecretResolver secretResolver;

    @ConfigProperty(name = "amsha.sandbox.strategy", defaultValue = "PODMAN")
    SandboxStrategy sandboxStrategy;

    @ConfigProperty(name = "amsha.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;

    @ConfigProperty(name = "amsha.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "amsha.sandbox.allow-insecure-fallback", defaultValue = "false")
    boolean allowInsecureFallback;

    public boolean isAllowInsecureFallback() {
        return allowInsecureFallback;
    }

    public int getPodmanFailureThreshold() {
        return circuitBreaker.getPodmanFailureThreshold();
    }

    public int getResetTimeoutSeconds() {
        return circuitBreaker.getResetTimeoutSeconds();
    }

    /** Startup availability check - degrades gracefully if Podman not installed. */
    @PostConstruct
    void checkPodmanAvailability() {
        if (SandboxStrategy.PODMAN == sandboxStrategy) {

            try {
                var pb = new ProcessBuilder(podmanPath, "--version");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                boolean ok = proc.waitFor(5, TimeUnit.SECONDS);
                if (!ok || proc.exitValue() != 0) {
                    log.warn("Podman not available - circuit open, falling back to NAMESPACE strategy.");
                    circuitBreaker.openCircuit();
                }
            } catch (Exception e) {
                log.warnf(
                        "Podman not found on PATH - isolation degraded to NAMESPACE strategy (error: %s)",
                        LogUtil.rootMessage(e));
                log.debugf("Podman check exception details: %s", LogUtil.trimmedStack(e));
                circuitBreaker.openCircuit();
            }
        }
    }

    /**
     * Primary entry point - routes to Podman, NAMESPACE, or NONE based on strategy + circuit state.
     *
     * @param sessionId       The workspace session ID.
     * @param workspaceRoot   The workspace root directory.
     * @param command         The command to execute.
     * @param requiredSecrets Map of envVar → vaultKey for secrets to inject.
     * @return SandboxResult indicating success, failure, or infra failure.
     */
    @Override
    public SandboxResult executeSandboxed(
            String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets) {

        try {
            if (!sandboxEnabled
                    || SandboxStrategy.NONE == sandboxStrategy
                    || SandboxStrategy.DIRECT == sandboxStrategy) {
                return SandboxResult.success(directExecutor.executeDirectly(
                        workspaceRoot,
                        rewriteContainerPaths(command, workspaceRoot),
                        resolveFallbackSecrets(requiredSecrets)));
            }

            if (SandboxStrategy.PODMAN == sandboxStrategy && circuitBreaker.shouldAttemptPodman()) {
                SandboxResult podmanResult = tryPodmanExecution(sessionId, workspaceRoot, command, requiredSecrets);
                if (podmanResult != null) {
                    return podmanResult;
                }
            }

            // Podman circuit is open (or strategy is NAMESPACE) - fall back to namespace isolation.
            // NOTE: requiredSecrets are NOT injected in namespace mode (see class-level Javadoc).
            try {
                return SandboxResult.success(namespaceExecutor.executeInNamespace(
                        workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
            } catch (Exception nsEx) {
                if (allowInsecureFallback) {
                    log.warnf(
                            "[SANDBOX] Namespace unavailable (%s) - degrading to host execution",
                            LogUtil.rootMessage(nsEx));
                    return fallbackToHostExecution(workspaceRoot, command, requiredSecrets);
                }
                throw nsEx;
            }
        } catch (ToolExecutionException e) {
            return SandboxResult.failure(e.getMessage());
        } catch (Exception e) {
            log.errorf(
                    "[SANDBOX] Unexpected infrastructure failure (sessionId=%s): %s",
                    sessionId, LogUtil.rootMessage(e));
            return SandboxResult.infraFailure("SANDBOX_OFFLINE: Infrastructure failure");
        }
    }

    /**
     * Rewrites container-side /workspace paths to the actual host workspaceRoot.
     * Only needed for NONE/NAMESPACE strategies where execution is NOT inside a container.
     * Podman mounts workspaceRoot as /workspace itself, so no rewriting needed there.
     *
     * Handles both standalone args (e.g. {@code /workspace/script.py}) and embedded
     * paths inside shell command strings (e.g. {@code sh -c "javac ... /workspace/X.java"}).
     */
    private List<String> rewriteContainerPaths(List<String> command, Path workspaceRoot) {
        final String containerRoot = "/workspace";
        final String hostRoot = workspaceRoot
                .toAbsolutePath()
                .toString()
                .replace("\\", "/"); // normalize Windows backslashes → forward slashes

        return command.stream()
                .map(arg -> {
                    if (arg.startsWith(containerRoot)) {
                        return hostRoot + arg.substring(containerRoot.length());
                    }
                    if (arg.contains(containerRoot)) {
                        return arg.replace(containerRoot, hostRoot);
                    }
                    return arg;
                })
                .toList();
    }

    private SandboxResult tryPodmanExecution(
            String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets)
            throws Exception {

        if (!circuitBreaker.shouldAttemptPodman()) {
            return null;
        }
        CircuitBreakerState stateAtCall = circuitBreaker.getState();
        try {
            String result = podmanExecutor.executeInContainer(sessionId, workspaceRoot, command, requiredSecrets);
            circuitBreaker.recordSuccess();
            return SandboxResult.success(result);

        } catch (SandboxImageMissingException e) {
            log.errorf("[SANDBOX] Image missing (session=%s): %s", sessionId, LogUtil.rootMessage(e));
            if (allowInsecureFallback) {
                return fallbackToHostExecution(workspaceRoot, command, requiredSecrets);
            }
            return SandboxResult.infraFailure("SANDBOX_OFFLINE: The sandbox image is missing. "
                    + "Please rebuild it: podman build --no-cache -t amsha-tool-runner "
                    + "-f ./sandbox/Dockerfile.toolrunner .");

        } catch (PodmanUnavailableException e) {
            String errMsg =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warnf("Podman unavailable (%s) - checking fallback for session %s", errMsg, sessionId);
            if (allowInsecureFallback) {
                return fallbackToHostExecution(workspaceRoot, command, requiredSecrets);
            }
            circuitBreaker.recordFailure(stateAtCall);
            return SandboxResult.infraFailure("SANDBOX_OFFLINE: Podman is unavailable - " + errMsg);

        } catch (IOException e) {
            String errMsg =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warnf("Podman unavailable (%s) - checking fallback for session %s", errMsg, sessionId);
            if (allowInsecureFallback) {
                return fallbackToHostExecution(workspaceRoot, command, requiredSecrets);
            }
            circuitBreaker.recordFailure(stateAtCall);
            return SandboxResult.infraFailure("SANDBOX_OFFLINE: Podman is unavailable - " + errMsg);

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            String errMsg =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.errorf("Sandbox execution failed unexpectedly (session=%s): %s", sessionId, errMsg);
            return SandboxResult.infraFailure("SANDBOX_OFFLINE: Unexpected error - " + errMsg);
        }
    }

    private SandboxResult fallbackToHostExecution(
            Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets) throws Exception {
        log.warn("[SANDBOX] allow-insecure-fallback enabled - degrading to host execution");
        return SandboxResult.success(directExecutor.executeDirectly(
                workspaceRoot, rewriteContainerPaths(command, workspaceRoot), resolveFallbackSecrets(requiredSecrets)));
    }

    private Map<String, byte[]> resolveFallbackSecrets(Map<String, String> requiredSecrets) {
        if (requiredSecrets == null || requiredSecrets.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return secretResolver.resolveSecrets(requiredSecrets);
        } catch (Exception e) {
            log.warnf("Failed to resolve sandbox secrets for fallback: %s", LogUtil.rootMessage(e));
            return Collections.emptyMap();
        }
    }
}
