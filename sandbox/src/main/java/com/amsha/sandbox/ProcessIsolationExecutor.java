package com.amsha.sandbox;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.core.sandbox.SandboxAttemptLifecycle;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxFailureMessages;
import com.amsha.core.sandbox.SandboxRecoveryEvidence;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.sandbox.SandboxCircuitBreaker.CircuitBreakerState;
import com.amsha.tool.api.ToolExecutionContext;

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
                        e.getClass().getSimpleName());
                log.debugf("Podman check failed (type=%s)", e.getClass().getSimpleName());
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
        return executeSandboxedInternal(
                sessionId, null, workspaceRoot, command, requiredSecrets, SandboxAttemptLifecycle.NONE);
    }

    @Override
    public SandboxResult executeSandboxed(
            ToolExecutionContext context,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets,
            SandboxAttemptLifecycle lifecycle) {
        return executeSandboxedInternal(
                context.sessionId(), context, workspaceRoot, command, requiredSecrets, lifecycle);
    }

    @Override
    public void releaseSettledAttempt(ToolExecutionContext context) {
        podmanExecutor.releaseSettledAttempt(context);
    }

    @Override
    public SandboxRecoveryEvidence inspectAttempt(
            String workspaceId, String attemptId, String executionHandle, String processStartIdentity) {
        if (executionHandle == null || executionHandle.isBlank()) {
            return new SandboxRecoveryEvidence.Missing("No execution handle recorded");
        }
        if (executionHandle.startsWith("podman-container:")) {
            String containerName = executionHandle.substring("podman-container:".length());
            String expectedContainerId = null;
            if (processStartIdentity != null && processStartIdentity.startsWith("container:")) {
                expectedContainerId = processStartIdentity.substring("container:".length());
            }
            return PodmanAttemptContainerSupport.inspectRecoveryEvidence(
                    podmanPath, containerName, workspaceId, attemptId, expectedContainerId);
        }
        if (executionHandle.startsWith("process:")) {
            if (attemptId != null && !executionHandle.equals("process:" + attemptId)) {
                return new SandboxRecoveryEvidence.OwnershipMismatch(
                        "Process execution handle '" + executionHandle + "' does not match attempt ID " + attemptId);
            }
            return inspectProcessAttempt(executionHandle, processStartIdentity);
        }
        return new SandboxRecoveryEvidence.Inconclusive("Unsupported execution handle format: " + executionHandle);
    }

    private SandboxRecoveryEvidence inspectProcessAttempt(String executionHandle, String processStartIdentity) {
        if (processStartIdentity == null || !processStartIdentity.startsWith("pid:")) {
            return new SandboxRecoveryEvidence.Inconclusive(
                    "Missing or invalid process start identity: " + processStartIdentity);
        }
        String[] parts = processStartIdentity.split(";");
        long pid;
        long startEpoch;
        try {
            pid = Long.parseLong(parts[0].substring("pid:".length()));
            if (parts.length > 1 && parts[1].startsWith("start:")) {
                startEpoch = Long.parseLong(parts[1].substring("start:".length()));
            } else {
                return new SandboxRecoveryEvidence.Inconclusive("Missing start timestamp in process identity");
            }
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return new SandboxRecoveryEvidence.Inconclusive("Malformed process identity: " + processStartIdentity);
        }

        Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
        if (phOpt.isEmpty()) {
            return new SandboxRecoveryEvidence.Missing("PID " + pid + " does not exist");
        }
        ProcessHandle handle = phOpt.get();
        if (!handle.isAlive()) {
            return new SandboxRecoveryEvidence.Missing("PID " + pid + " is not alive");
        }
        Optional<Instant> startInstantOpt = handle.info().startInstant();
        if (startInstantOpt.isEmpty() || startInstantOpt.get().toEpochMilli() != startEpoch) {
            return new SandboxRecoveryEvidence.OwnershipMismatch(
                    "PID " + pid + " start time does not match recorded identity");
        }
        return new SandboxRecoveryEvidence.RunningVerified(executionHandle, processStartIdentity);
    }

    @Override
    public boolean terminateRunningAttempt(
            String workspaceId, String attemptId, String executionHandle, String processStartIdentity) {
        if (executionHandle == null || executionHandle.isBlank()) {
            return true;
        }
        if (executionHandle.startsWith("podman-container:")) {
            String containerName = executionHandle.substring("podman-container:".length());
            String expectedContainerId = null;
            if (processStartIdentity != null && processStartIdentity.startsWith("container:")) {
                expectedContainerId = processStartIdentity.substring("container:".length());
            }
            return PodmanAttemptContainerSupport.terminateContainer(
                    podmanPath, containerName, workspaceId, attemptId, expectedContainerId);
        }
        if (executionHandle.startsWith("process:")) {
            if (attemptId != null && !executionHandle.equals("process:" + attemptId)) {
                return false;
            }
            return terminateProcessAttempt(processStartIdentity);
        }
        return false;
    }

    private boolean terminateProcessAttempt(String processStartIdentity) {
        if (processStartIdentity == null || !processStartIdentity.startsWith("pid:")) {
            return true;
        }
        String[] parts = processStartIdentity.split(";");
        long pid;
        long startEpoch;
        try {
            pid = Long.parseLong(parts[0].substring("pid:".length()));
            if (parts.length > 1 && parts[1].startsWith("start:")) {
                startEpoch = Long.parseLong(parts[1].substring("start:".length()));
            } else {
                return true;
            }
        } catch (Exception _) {
            return true;
        }
        Optional<ProcessHandle> phOpt = ProcessHandle.of(pid);
        if (phOpt.isEmpty()) {
            return true;
        }
        ProcessHandle handle = phOpt.get();
        if (!handle.isAlive()) {
            return true;
        }
        Optional<Instant> startInstantOpt = handle.info().startInstant();
        if (startInstantOpt.isPresent() && startInstantOpt.get().toEpochMilli() == startEpoch) {
            handle.destroyForcibly();
            try {
                handle.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception _) {
                // ignore
            }
            return !handle.isAlive();
        }
        return true;
    }

    private SandboxResult executeSandboxedInternal(
            String sessionId,
            ToolExecutionContext context,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets,
            SandboxAttemptLifecycle lifecycle) {

        try {
            if (!sandboxEnabled
                    || SandboxStrategy.NONE == sandboxStrategy
                    || SandboxStrategy.DIRECT == sandboxStrategy) {
                if (context == null) {
                    return SandboxResult.success(directExecutor.executeDirectly(
                            workspaceRoot,
                            rewriteContainerPaths(command, workspaceRoot),
                            resolveFallbackSecrets(requiredSecrets)));
                }
                return SandboxResult.success(directExecutor.executeDirectly(
                        workspaceRoot,
                        rewriteContainerPaths(command, workspaceRoot),
                        resolveFallbackSecrets(requiredSecrets),
                        processExecutionHandle(context),
                        lifecycle));
            }

            if (SandboxStrategy.PODMAN == sandboxStrategy && circuitBreaker.shouldAttemptPodman()) {
                SandboxResult podmanResult =
                        tryPodmanExecution(sessionId, context, workspaceRoot, command, requiredSecrets, lifecycle);
                if (podmanResult != null) {
                    return podmanResult;
                }
            }

            // Podman circuit is open (or strategy is NAMESPACE) - fall back to namespace isolation.
            // NOTE: requiredSecrets are NOT injected in namespace mode (see class-level Javadoc).
            try {
                if (context == null) {
                    return SandboxResult.success(namespaceExecutor.executeInNamespace(
                            workspaceRoot, rewriteContainerPaths(command, workspaceRoot)));
                }
                return SandboxResult.success(namespaceExecutor.executeInNamespace(
                        workspaceRoot,
                        rewriteContainerPaths(command, workspaceRoot),
                        processExecutionHandle(context),
                        lifecycle));
            } catch (Exception nsEx) {
                if (allowInsecureFallback && !lifecycle.hasLaunchIntent()) {
                    log.warnf(
                            "[SANDBOX] Namespace unavailable (type=%s) - degrading to host execution",
                            nsEx.getClass().getSimpleName());
                    return fallbackToHostExecution(workspaceRoot, command, requiredSecrets, context, lifecycle);
                }
                throw nsEx;
            }
        } catch (ToolExecutionException e) {
            log.warnf("[SANDBOX] Tool execution failed with exit code %d (sessionId=%s)", e.exitCode(), sessionId);
            return SandboxResult.failure();
        } catch (Exception e) {
            log.errorf(
                    "[SANDBOX] Unexpected infrastructure failure (sessionId=%s, type=%s)",
                    sessionId, e.getClass().getSimpleName());
            return SandboxResult.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);
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
            String sessionId,
            ToolExecutionContext context,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets,
            SandboxAttemptLifecycle lifecycle)
            throws Exception {

        if (!circuitBreaker.shouldAttemptPodman()) {
            return null;
        }
        CircuitBreakerState stateAtCall = circuitBreaker.getState();
        try {
            String result = context == null
                    ? podmanExecutor.executeInContainer(sessionId, workspaceRoot, command, requiredSecrets)
                    : podmanExecutor.executeInContainer(context, workspaceRoot, command, requiredSecrets, lifecycle);
            circuitBreaker.recordSuccess();
            return SandboxResult.success(result);

        } catch (SandboxImageMissingException e) {
            log.errorf("[SANDBOX] Image missing (session=%s)", sessionId);
            if (allowInsecureFallback && !lifecycle.hasLaunchIntent()) {
                return fallbackToHostExecution(workspaceRoot, command, requiredSecrets, context, lifecycle);
            }
            return SandboxResult.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);

        } catch (PodmanUnavailableException e) {
            log.warnf(
                    "Podman unavailable (type=%s) - checking fallback for session %s",
                    e.getClass().getSimpleName(), sessionId);
            if (allowInsecureFallback && !lifecycle.hasLaunchIntent()) {
                return fallbackToHostExecution(workspaceRoot, command, requiredSecrets, context, lifecycle);
            }
            circuitBreaker.recordFailure(stateAtCall);
            return SandboxResult.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);

        } catch (IOException e) {
            log.warnf(
                    "Podman unavailable (type=%s) - checking fallback for session %s",
                    e.getClass().getSimpleName(), sessionId);
            if (allowInsecureFallback && !lifecycle.hasLaunchIntent()) {
                return fallbackToHostExecution(workspaceRoot, command, requiredSecrets, context, lifecycle);
            }
            circuitBreaker.recordFailure(stateAtCall);
            return SandboxResult.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);

        } catch (ConfirmedSandboxTimeoutException e) {
            return SandboxResult.timedOut(e.output());

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(
                    "Sandbox execution failed unexpectedly (session=%s, type=%s)",
                    sessionId, e.getClass().getSimpleName());
            return SandboxResult.infraFailure(SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE);
        }
    }

    private SandboxResult fallbackToHostExecution(
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets,
            ToolExecutionContext context,
            SandboxAttemptLifecycle lifecycle)
            throws Exception {
        log.warn("[SANDBOX] allow-insecure-fallback enabled - degrading to host execution");
        if (context == null) {
            return SandboxResult.success(directExecutor.executeDirectly(
                    workspaceRoot,
                    rewriteContainerPaths(command, workspaceRoot),
                    resolveFallbackSecrets(requiredSecrets)));
        }
        return SandboxResult.success(directExecutor.executeDirectly(
                workspaceRoot,
                rewriteContainerPaths(command, workspaceRoot),
                resolveFallbackSecrets(requiredSecrets),
                processExecutionHandle(context),
                lifecycle));
    }

    private static String processExecutionHandle(ToolExecutionContext context) {
        return context == null || context.attemptId() == null ? "process:legacy" : "process:" + context.attemptId();
    }

    private Map<String, byte[]> resolveFallbackSecrets(Map<String, String> requiredSecrets) {
        if (requiredSecrets == null || requiredSecrets.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return secretResolver.resolveSecrets(requiredSecrets);
        } catch (Exception e) {
            log.warnf(
                    "Failed to resolve sandbox secrets for fallback (type=%s)",
                    e.getClass().getSimpleName());
            return Collections.emptyMap();
        }
    }
}
