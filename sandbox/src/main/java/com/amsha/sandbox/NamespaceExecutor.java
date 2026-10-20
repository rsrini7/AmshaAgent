package com.amsha.sandbox;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NamespaceExecutor {

    private static final Logger log = Logger.getLogger(NamespaceExecutor.class);

    @ConfigProperty(name = "amsha.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds = 30;

    /**
     * Strategy B: Linux unshare namespaces (lighter, no Podman needed).
     * Falls back gracefully on non-Linux (dev/macOS/Windows).
     */
    public String executeInNamespace(Path workspaceRoot, List<String> command) throws Exception {
        boolean isLinux =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
        ProcessBuilder pb;
        if (isLinux) {
            pb = new ProcessBuilder(buildUnshareCommand(workspaceRoot, command));
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
        var process = startProcess(pb);

        try {
            ProcessOutput processOutput = waitForProcess(process);
            String output = processOutput.output();
            if (!processOutput.finished()) {
                throw new SecurityException("Tool timed out after " + timeoutSeconds + "s");
            }
            String result = output.trim();
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

    static List<String> buildUnshareCommand(Path workspaceRoot, List<String> command) {
        var fullCommand = new ArrayList<>(List.of(
                "unshare",
                "--pid",
                "--fork",
                "--mount",
                "--net",
                "--map-root-user",
                "chroot",
                workspaceRoot.toAbsolutePath().toString()));
        fullCommand.addAll(command);
        return List.copyOf(fullCommand);
    }

    Process startProcess(ProcessBuilder processBuilder) throws IOException {
        return processBuilder.start();
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
                    "Unable to enumerate sandbox process descendants; forcing parent termination (%s)",
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
}
