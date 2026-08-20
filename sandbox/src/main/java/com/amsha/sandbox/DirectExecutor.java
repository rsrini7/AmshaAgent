package com.amsha.sandbox;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DirectExecutor {

    private static final Logger log = Logger.getLogger(DirectExecutor.class);

    @ConfigProperty(name = "amsha.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds = 30;

    /** Dev mode: no isolation, direct execution. */
    public String executeDirectly(Path workspaceRoot, List<String> command, Map<String, byte[]> secrets)
            throws Exception {
        List<String> finalCommand = fixupCommandForWindows(command);
        var pb = new ProcessBuilder(finalCommand);
        File workspaceDir = workspaceRoot.toFile();
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs();
            log.warnf("PIE Workspace dir missing - recreated: %s", workspaceDir.getAbsolutePath());
        }
        pb.directory(workspaceDir);
        pb.redirectErrorStream(true);
        if (secrets != null && !secrets.isEmpty()) {
            for (var entry : secrets.entrySet()) {
                pb.environment().put(entry.getKey(), new String(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        var process = pb.start();

        // Drain stdout BEFORE waitFor() to avoid missing buffered output on Windows
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

    /**
     * Common Windows FIX: 'python3' is often a broken Microsoft Store shim.
     * Alias it to 'python' if 'python3 --version' fails but 'python' works.
     */
    private List<String> fixupCommandForWindows(List<String> command) {
        if (command == null || command.isEmpty()) return command;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return command;

        String exe = command.get(0);
        if ("python3".equals(exe)) {
            // Check if python3 is actually callable. If not, swap to python.
            if (!canRun(exe)) {
                var fixed = new ArrayList<>(command);
                fixed.set(0, "python");
                return fixed;
            }
        }
        return command;
    }

    private boolean canRun(String command) {
        try {
            var pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            return proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
