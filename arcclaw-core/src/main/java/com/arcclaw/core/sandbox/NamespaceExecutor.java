package com.arcclaw.core.sandbox;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NamespaceExecutor {

    @ConfigProperty(name = "arcclaw.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    /**
     * Strategy B: Linux unshare namespaces (lighter, no Podman needed).
     * Falls back gracefully on non-Linux (dev/macOS/Windows).
     */
    public String executeInNamespace(Path workspaceRoot, List<String> command) throws Exception {
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
            File workspaceDir = workspaceRoot.toFile();
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs();
                // log not available in this simple class, but following user request pattern
                System.err.println("PIE Workspace dir missing — recreated: " + workspaceDir.getAbsolutePath());
            }
            pb.directory(workspaceDir);
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
}
