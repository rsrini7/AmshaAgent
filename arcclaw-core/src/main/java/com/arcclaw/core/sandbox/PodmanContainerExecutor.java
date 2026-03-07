package com.arcclaw.core.sandbox;

import com.arcclaw.core.observability.AgentOtelContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private static final String CONTAINER_PIDS_LIMIT = "64";

    private final Set<String> activeContainerNames = ConcurrentHashMap.newKeySet();

    /**
     * Fix G2: Graceful container shutdown on JVM SIGTERM / @ApplicationScoped destroy.
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
}
