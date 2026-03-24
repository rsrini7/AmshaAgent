package com.arcclaw.core.sandbox;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class DirectExecutor {

    @ConfigProperty(name = "arcclaw.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    /** Dev mode: no isolation, direct execution. */
    public String executeDirectly(Path workspaceRoot, List<String> command) throws Exception {
        var pb = new ProcessBuilder(command);
        File workspaceDir = workspaceRoot.toFile();
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs();
            System.err.println("PIE Workspace dir missing — recreated: " + workspaceDir.getAbsolutePath());
        }
        pb.directory(workspaceDir);
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
