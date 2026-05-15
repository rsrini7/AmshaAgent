package com.arcclaw.core.sandbox;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NamespaceExecutor {

    private static final Logger log = Logger.getLogger(NamespaceExecutor.class);

    @ConfigProperty(name = "arcclaw.sandbox.timeout-seconds", defaultValue = "30")
    private long timeoutSeconds;

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
                log.warnf("PIE Workspace dir missing - recreated: %s", workspaceDir.getAbsolutePath());
            }
            pb.directory(workspaceDir);
        }
        pb.redirectErrorStream(true);
        var process = pb.start();
        
        // Drain stdout BEFORE waitFor() to capture buffered output correctly
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
        }
        String result = output.trim();
        if (process.exitValue() != 0) {
            throw new ToolExecutionException("Tool failed (exit " + process.exitValue() + "): " + result);
        }
        return result.isBlank() ? "(no execution output)" : result;
    }
}
