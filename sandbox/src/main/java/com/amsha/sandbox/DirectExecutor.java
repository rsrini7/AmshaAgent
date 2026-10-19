package com.amsha.sandbox;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.tools.LanguageIds;

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

        try {
            ProcessOutput processOutput = waitForProcess(process);
            if (!processOutput.finished()) {
                throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
            }
            String result = processOutput.output().trim();
            if (process.exitValue() != 0) {
                throw new ToolExecutionException(
                        result.isBlank() ? "Tool process exited with code " + process.exitValue() : result,
                        process.exitValue());
            }
            return result.isBlank() ? "(no execution output)" : result;
        } finally {
            terminateProcess(process, null);
        }
    }

    private ProcessOutput waitForProcess(Process process) throws InterruptedException {
        AtomicReference<String> output = new AtomicReference<>("");
        Thread reader = Thread.startVirtualThread(() -> {
            try {
                output.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException _) {
                // The process is being terminated or its output stream was closed.
            }
        });

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished || Thread.currentThread().isInterrupted()) {
                terminateProcess(process, reader);
                return new ProcessOutput(false, output.get());
            }

            reader.join(1_000);
            if (reader.isAlive()) {
                terminateProcess(process, reader);
                return new ProcessOutput(false, output.get());
            }
            return new ProcessOutput(true, output.get());
        } catch (InterruptedException e) {
            terminateProcess(process, reader);
            throw e;
        }
    }

    private void closeOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException _) {
            // Best effort while terminating the child process.
        }
    }

    private void terminateProcess(Process process, Thread reader) {
        try {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException e) {
            log.debugf(
                    "Unable to enumerate direct process descendants; forcing parent termination (%s)",
                    e.getClass().getSimpleName());
        }
        process.destroyForcibly();
        closeOutput(process);
        if (reader != null && reader.isAlive()) {
            try {
                reader.join(1_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ProcessOutput(boolean finished, String output) {}

    /**
     * Common Windows FIX: 'python3' is often a broken Microsoft Store shim.
     * Alias it to 'python' if 'python3 --version' fails but 'python' works.
     */
    private List<String> fixupCommandForWindows(List<String> command) {
        if (command == null || command.isEmpty()) return command;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) return command;

        String exe = command.get(0);
        if (LanguageIds.PYTHON3.equals(exe)) {
            // Check if python3 is actually callable. If not, swap to python.
            if (!canRun(exe)) {
                var fixed = new ArrayList<>(command);
                fixed.set(0, LanguageIds.PYTHON);
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
