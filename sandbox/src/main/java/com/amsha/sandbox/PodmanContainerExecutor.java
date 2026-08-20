package com.amsha.sandbox;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.common.util.LogUtil;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.util.PathSanitizer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class PodmanContainerExecutor {

    private static final String PODMAN_ENV_FLAG = "--env";

    private static final Logger log = Logger.getLogger(PodmanContainerExecutor.class);

    private volatile boolean podmanAvailable = false;
    private volatile long lastChecked = 0;

    @Inject
    AgentOtelContext otelContext;

    @Inject
    SandboxSecretResolver secretResolver;

    @ConfigProperty(name = "amsha.sandbox.image")
    Optional<String> configuredSandboxImage = Optional.empty();

    @ConfigProperty(name = "amsha.sandbox.podman-image", defaultValue = SandboxImageResolver.DEFAULT_IMAGE)
    Optional<String> configuredPodmanImage = Optional.empty();

    String getSandboxImage() {
        return SandboxImageResolver.resolveImage(configuredSandboxImage, configuredPodmanImage);
    }

    @ConfigProperty(name = "amsha.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @ConfigProperty(name = "amsha.sandbox.network-timeout-seconds", defaultValue = "60")
    long networkTimeoutSeconds;

    @ConfigProperty(name = "amsha.sandbox.memory-limit", defaultValue = "512m")
    String containerMemoryLimit;

    @ConfigProperty(name = "amsha.sandbox.cpu-limit", defaultValue = "0.5")
    String containerCpuLimit;

    @ConfigProperty(name = "amsha.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "amsha.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @ConfigProperty(name = "amsha.sandbox.network", defaultValue = "bridge")
    String containerNetwork;

    @ConfigProperty(name = "amsha.sandbox.tool-runner-user", defaultValue = "sandbox")
    String toolRunnerUser;

    private static final String CONTAINER_PIDS_LIMIT = "128";

    private final Set<String> activeContainerNames = ConcurrentHashMap.newKeySet();

    void onStartup(@Observes StartupEvent ev) {
        TmpfsSecretTransport.sweepStaleTmpFilesStatic();
    }

    /**
     * Graceful container shutdown on JVM SIGTERM / @ApplicationScoped destroy.
     * <p>
     * Stops all tracked containers with a 10-second grace period
     * (matching Kubernetes terminationGracePeriodSeconds default).
     */
    @PreDestroy
    public void stopAllContainersOnShutdown() {
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
                    log.warnf(
                            "Container %s did not stop within grace period (exit=%d).",
                            containerName, stopped ? proc.exitValue() : -1);
                }
            } catch (Exception e) {
                log.warnf("Failed to stop container %s: %s", containerName, LogUtil.rootMessage(e));
            }
        }
        activeContainerNames.clear();
    }

    public String executeInContainer(
            String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets)
            throws Exception {

        var resolvedSecrets = secretResolver.resolveSecrets(requiredSecrets);

        checkPodmanAvailable();
        checkImageAvailable();

        // Use the unified Tier 1 tmpfs secure transport.
        // The transport strictly zeroes the memory arrays when closed.
        try (var transport = new TmpfsSecretTransport(sessionId)) {
            List<String> transportArgs = transport.prepare(resolvedSecrets);

            String containerName = "zc-" + sessionId.substring(0, Math.min(8, sessionId.length())) + "-"
                    + Long.toHexString(System.nanoTime());

            Span span = otelContext.childSpan(sessionId, AgentOTelConstants.SPAN_TOOL_CONTAINER_EXEC);
            span.setAttribute(AgentOTelConstants.ATTR_CONTAINER_IMAGE, getSandboxImage());
            span.setAttribute(AgentOTelConstants.ATTR_CONTAINER_NAME, containerName);
            span.setAttribute(AgentOTelConstants.ATTR_SANDBOX_STRATEGY, SandboxStrategy.PODMAN.name());

            activeContainerNames.add(containerName);
            try (Scope scope = span.makeCurrent()) {
                List<String> podmanCmd = buildPodmanCmd(containerName, transportArgs, workspaceRoot, command);
                var process = prepareAndStartProcess(sessionId, podmanCmd, transport);
                String result = readSandboxOutputAndGetResult(process, command);
                span.setStatus(StatusCode.OK);
                return result;
            } catch (Exception e) {
                span.recordException(e);
                span.setStatus(
                        StatusCode.ERROR,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                throw e;
            } finally {
                span.end();
                activeContainerNames.remove(containerName);
            }
        }
    }

    private List<String> buildPodmanCmd(
            String containerName, List<String> transportArgs, Path workspaceRoot, List<String> command) {
        var podmanCmd = new ArrayList<>(List.of(
                podmanPath,
                "run",
                "--rm",
                "--name",
                containerName,
                "--pull",
                "never",
                "--network=" + containerNetwork,
                "--read-only",
                "--tmpfs",
                "/tmp:rw,nosuid,size=128m",
                "--tmpfs",
                "/home/" + toolRunnerUser + ":rw,nosuid,size=128m",
                "--security-opt=no-new-privileges",
                "--cap-drop=ALL",
                "-v",
                workspaceRoot.toAbsolutePath() + ":/workspace:ro",
                "--user=" + toolRunnerUser + ":" + toolRunnerUser,
                PODMAN_ENV_FLAG,
                "GOCACHE=/tmp/go-cache",
                PODMAN_ENV_FLAG,
                "GOPATH=/tmp/go-path",
                PODMAN_ENV_FLAG,
                "GOMAXPROCS=1",
                PODMAN_ENV_FLAG,
                "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/tmp",
                PODMAN_ENV_FLAG,
                "GH_CONFIG_DIR=/tmp/gh-config",
                PODMAN_ENV_FLAG,
                "GH_NO_UPDATE_NOTIFIER=1",
                PODMAN_ENV_FLAG,
                "GH_PROMPT_DISABLED=1",
                "--pids-limit=" + CONTAINER_PIDS_LIMIT,
                "--ulimit=nofile=256:256"));

        if (containerMemoryLimit != null && !containerMemoryLimit.isBlank()) {
            podmanCmd.add("--memory=" + containerMemoryLimit);
        }
        if (containerCpuLimit != null && !containerCpuLimit.isBlank()) {
            podmanCmd.add("--cpus=" + containerCpuLimit);
        }

        podmanCmd.addAll(transportArgs);
        podmanCmd.add(getSandboxImage());
        podmanCmd.addAll(command);
        return podmanCmd;
    }

    private Process prepareAndStartProcess(String sessionId, List<String> podmanCmd, TmpfsSecretTransport transport)
            throws IOException {
        log.infof("[Sandbox] Executing container process with image %s (args=%d)", getSandboxImage(), podmanCmd.size());
        var pb = new ProcessBuilder(podmanCmd);

        // Use PathSanitizer to match ToolRegistry.invokeSandboxed() which sanitizes colons in session IDs.
        File workspaceDir = Path.of(workspaceRootDir, PathSanitizer.sanitizeForFilesystem(sessionId))
                .toFile();
        if (!workspaceDir.exists()) {
            boolean created = workspaceDir.mkdirs();
            if (!created) {
                throw new IOException("Failed to create workspace directory: " + workspaceDir.getAbsolutePath());
            }
            log.warnf("PIE Workspace dir missing - recreated: %s", workspaceDir.getAbsolutePath());
        }

        pb.directory(workspaceDir);
        var process = pb.start();
        transport.deliver(process);
        return process;
    }

    private String readSandboxOutputAndGetResult(Process process, List<String> command) throws Exception {
        var stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warnf("Failed to drain sandbox stdout: %s", LogUtil.rootMessage(e));
                return "";
            }
        });
        var stderrFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warnf("Failed to drain sandbox stderr: %s", LogUtil.rootMessage(e));
                return "";
            }
        });

        long effectiveTimeout = resolveTimeout(command);
        boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            throw new TimeoutException("Tool timed out after " + effectiveTimeout + "s");
        }

        String stdout = "";
        String stderr = "";
        try {
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.errorf("Error retrieving sandbox output: %s", LogUtil.rootMessage(e));
        }

        int exitCode = process.exitValue();

        // Filter benign JVM informational lines from stderr
        if (stderr != null && !stderr.isBlank()) {
            stderr = stderr.replaceAll("(?m)^Picked up JAVA_TOOL_OPTIONS:.*$", "")
                    .trim();
        }

        if (exitCode != 0) {
            String raw = (stdout != null && !stdout.isBlank()) ? stdout : stderr;

            if (raw != null && (raw.contains("not known") || raw.contains("not found"))) {
                log.warnf("**====================================================================**");
                log.warnf("[SANDBOX-WARNING] Sandbox image '%s' is missing from local storage.", getSandboxImage());
                log.warnf("To use the secure Podman sandbox, please build the runner image:");
                log.warnf("  podman build -t amsha-tool-runner -f ./sandbox/Dockerfile.toolrunner .");
                log.warnf("**====================================================================**");
                throw new SandboxImageMissingException("Missing sandbox image: " + getSandboxImage());
            }

            if (exitCode == 125) {
                throw new IOException("Podman engine failure (exit 125): " + raw);
            }

            String cleanOutput = (raw == null || raw.isBlank()) ? "(tool returned no output)" : raw.trim();
            throw new ToolExecutionException(cleanOutput, exitCode);
        }

        String result = (stdout != null && !stdout.isBlank()) ? stdout : stderr;
        return (result == null || result.isBlank()) ? "(tool returned no output)" : result;
    }

    private long resolveTimeout(List<String> command) {
        // Detect network calls: gh, curl, wget, python -m pip, etc.
        if (!command.isEmpty()) {
            String first = command.get(0).toLowerCase(Locale.ROOT);
            if (first.equals("gh") || first.equals("curl") || first.equals("wget")) {
                return networkTimeoutSeconds;
            }
        }
        return timeoutSeconds;
    }

    private void checkImageAvailable() throws SandboxImageMissingException {
        try {
            // Using inspect is more reliable across name forms (bare vs localhost/ prefix).
            Process p = new ProcessBuilder(podmanPath, "image", "inspect", getSandboxImage(), "--format", "{{.Id}}")
                    .start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS);
            if (!ok || p.exitValue() != 0) {
                throw new SandboxImageMissingException(
                        "Sandbox image '" + getSandboxImage() + "' missing from Podman storage.");
            }
        } catch (IOException | InterruptedException e) {
            throw new SandboxImageMissingException("Failed to check sandbox image: " + e.getMessage());
        }
    }

    private void checkPodmanAvailable() throws PodmanUnavailableException {
        long now = System.currentTimeMillis();
        if (now - lastChecked < 60_000) {
            if (!podmanAvailable) throw new PodmanUnavailableException("Podman recently checked and unavailable");
            return;
        }

        try {
            Process p = new ProcessBuilder(podmanPath, "info")
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS);
            if (!ok || p.exitValue() != 0) {
                podmanAvailable = false;
                lastChecked = now;
                throw new PodmanUnavailableException("podman info failed or timed out");
            }
            podmanAvailable = true;
            lastChecked = now;
        } catch (IOException | InterruptedException e) {
            podmanAvailable = false;
            lastChecked = now;
            throw new PodmanUnavailableException("Podman not reachable: " + e.getMessage());
        }
    }
}
