package com.arcclaw.core.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.observability.AgentOtelContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PodmanContainerExecutor {

    private static final Logger log = Logger.getLogger(PodmanContainerExecutor.class);

    @Inject
    AgentOtelContext otelContext;

    @Inject
    SandboxSecretResolver secretResolver;

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

    @ConfigProperty(name = "arcclaw.sandbox.network", defaultValue = "bridge")
    String containerNetwork;

    @ConfigProperty(name = "arcclaw.sandbox.secret-transport", defaultValue = "tmpfs")
    String secretTransportMode;

    @ConfigProperty(name = "arcclaw.sandbox.tool-runner-user", defaultValue = "65534")
    String toolRunnerUser;

    private static final String CONTAINER_PIDS_LIMIT = "64";

    private final Set<String> activeContainerNames = ConcurrentHashMap.newKeySet();

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
                    log.warnf("Container %s did not stop within grace period (exit=%d).",
                            containerName, stopped ? proc.exitValue() : -1);
                }
            } catch (Exception e) {
                log.warnf("Failed to stop container %s: %s", containerName, e.getMessage());
            }
        }
        activeContainerNames.clear();
    }

    public String executeInContainer(
            String sessionId,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets) throws Exception {

        var resolvedSecrets = secretResolver.resolveSecrets(requiredSecrets);

        // Use the unified Tier 1 tmpfs secure transport.
        // The transport strictly zeroes the memory arrays when closed.
        try (var transport = new TmpfsSecretTransport(sessionId, tmpfsPath, secretTransportMode)) {
            List<String> transportArgs = transport.prepare(resolvedSecrets);

            // Deterministic container name for tracking + graceful stop on shutdown.
            // Format: zc-<sessionId truncated to 8 chars>-<nonce> to stay within 64-char limit.
            String containerName = "zc-" + sessionId.substring(0, Math.min(8, sessionId.length()))
                    + "-" + Long.toHexString(System.nanoTime());

            Span span = otelContext.childSpan(sessionId, "tool.container_exec");
            span.setAttribute("container.image", containerImage);
            span.setAttribute("container.name", containerName);
            span.setAttribute("sandbox.strategy", SandboxStrategy.PODMAN.name());


            // Track before start — avoids race on early failure.
            activeContainerNames.add(containerName);
            try (Scope _ = span.makeCurrent()) {
                var podmanCmd = new ArrayList<>(List.of(
                        podmanPath, "run", "--rm",
                        "--name", containerName,
                        "--pull", "never",
                        "--network=" + containerNetwork,
                        "--read-only",
                        "--tmpfs", "/tmp:rw,nosuid,size=128m",
                        "--security-opt=no-new-privileges",
                        "--cap-drop=ALL",
                        "-v", workspaceRoot.toAbsolutePath() + ":/workspace:ro",
                        "--user=" + toolRunnerUser + ":" + toolRunnerUser,
                        "--pids-limit=" + CONTAINER_PIDS_LIMIT, // block fork bombs
                        "--ulimit=nofile=256:256"              // limit open file descriptors
                ));

                if (containerMemoryLimit != null && !containerMemoryLimit.isBlank()) {
                    podmanCmd.add("--memory=" + containerMemoryLimit);
                }
                if (containerCpuLimit != null && !containerCpuLimit.isBlank()) {
                    podmanCmd.add("--cpus=" + containerCpuLimit);
                }

                // Inject the transport args (e.g. --env-file /run/arcclaw/secrets/<nonce>.env)
                podmanCmd.addAll(transportArgs);

                podmanCmd.add(containerImage);
                podmanCmd.addAll(command);

                log.infof("[Sandbox] Executing: %s", String.join(" ", podmanCmd));

                var pb = new ProcessBuilder(podmanCmd);

                // FIX: check mkdirs() return value — silent failure leaves ProcessBuilder
                // pointing at a non-existent directory, producing a confusing OS error.
                File workspaceDir = Path.of(workspaceRootDir, sessionId).toFile();
                if (!workspaceDir.exists()) {
                    boolean created = workspaceDir.mkdirs();
                    if (!created) {
                        throw new IOException(
                            "Failed to create workspace directory: " + workspaceDir.getAbsolutePath());
                    }
                    log.warnf("PIE Workspace dir missing — recreated: %s",
                              workspaceDir.getAbsolutePath());
                }

                pb.directory(workspaceDir);
                pb.redirectErrorStream(true); // merge stderr into stdout — prevents output buffer deadlock

                var process = pb.start();
                transport.deliver(process);

                // FIX: waitFor() BEFORE readAllBytes().
                //
                // The original order was: readAllBytes() → waitFor().
                // readAllBytes() blocks until the process stream is closed (i.e., the process
                // has already exited), so waitFor() would always return true immediately and
                // the timeout check `if (!finished)` was unreachable for hanging processes.
                //
                // Correct order:
                //   1. waitFor(timeout) — enforces the deadline.
                //   2. If timed out → destroyForcibly() → throw.
                //   3. readAllBytes() — safe after process exits; output is buffered in the pipe
                //      and available regardless of whether we read before or after exit.
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    throw new ToolExecutionException("Tool timed out after " + timeoutSeconds + "s");
                }

                // Process has exited — drain the buffered output now.
                var output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.exitValue();

                if (exitCode != 0) {
                    // Intercept the "Image Not Found" case.
                    // FIX: use containerImage field instead of hardcoded literal
                    // so the warning stays accurate if the image name is overridden in config.
                    if (output.contains("not known") || output.contains("not found")) {
                        log.warnf("**====================================================================**");
                        log.warnf("[SANDBOX-WARNING] Sandbox image '%s' is missing from local storage.",
                                  containerImage);
                        log.warnf("To use the secure Podman sandbox, please build the runner image:");
                        log.warnf("  podman build -t arcclaw-tool-runner -f ./sandbox/Dockerfile.toolrunner .");
                        log.warnf("**====================================================================**");
                        throw new SandboxImageMissingException("Missing sandbox image: " + containerImage);
                    }

                    // Handle other infrastructure failures (exit 125)
                    if (exitCode == 125) {
                        throw new IOException("Podman engine failure (exit 125): " + output);
                    }

                    // Specific exit code for tool logic failure (standard for LLM tools)
                    throw new ToolExecutionException(output, exitCode);
                }

                span.setStatus(StatusCode.OK);
                return output;

            } catch (Exception e) {
                span.recordException(e);
                // FIX: null-guard e.getMessage() — some exception types (e.g. NullPointerException)
                // return null from getMessage(), which causes OpenTelemetry to throw internally.
                span.setStatus(StatusCode.ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                throw e;
            } finally {
                span.end();
                activeContainerNames.remove(containerName); // deregister on normal exit or exception
            }
        }
    }
}