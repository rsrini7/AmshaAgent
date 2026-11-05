package com.amsha.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.tool.api.ToolExecutionContext;

/** Owns inspection and cleanup operations for attempt-labeled Podman containers. */
final class PodmanAttemptContainerSupport {
    private static final int MAX_COMMAND_OUTPUT_BYTES = 1_048_576;

    private PodmanAttemptContainerSupport() {}

    static List<String> buildAttemptCommand(
            List<String> command, String containerName, String workspaceId, String attemptId) {
        var attemptCommand = new ArrayList<>(command);
        attemptCommand.remove("--rm");
        attemptCommand.add(2, "--detach");
        int labelsIndex = attemptCommand.indexOf(containerName) + 1;
        attemptCommand.add(labelsIndex, "--label=amsha.workspace-id=" + encodeLabel(workspaceId));
        attemptCommand.add(labelsIndex + 1, "--label=amsha.tool-attempt-id=" + attemptId);
        return List.copyOf(attemptCommand);
    }

    static String attemptContainerName(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("Sandbox attempt identity is required.");
        }
        return "zc-" + attemptId.replace("-", "");
    }

    static String awaitOwnedContainerStart(
            String podmanPath, String containerName, ToolExecutionContext context, Process startProcess)
            throws Exception {
        Optional<ContainerInfo> inspected = inspectOwnedContainer(podmanPath, containerName, context);
        if (inspected.isEmpty()) {
            throw new IOException("Podman did not confirm the attempt container start.");
        }
        ContainerInfo info = inspected.get();
        String startedAt = info.startedAt();
        if (!("running".equals(info.status()) || "exited".equals(info.status()))
                || startedAt == null
                || startedAt.startsWith("0001-01-01")) {
            throw new IOException("Podman did not confirm the attempt container start.");
        }
        if (startProcess.isAlive()) {
            startProcess.getInputStream().close();
            startProcess.getErrorStream().close();
        }
        return info.id();
    }

    static String awaitContainer(
            String podmanPath, long timeoutSeconds, String containerName, ToolExecutionContext context)
            throws Exception {
        CommandResult waited = runPodmanCommand(List.of(podmanPath, "wait", containerName), timeoutSeconds);
        if (!waited.finished()) {
            String output = stopAndCollectOutput(podmanPath, containerName, context);
            if (output == null) {
                throw new TimeoutException("Tool timed out and the owned container stop was not confirmed.");
            }
            throw new ConfirmedSandboxTimeoutException(output);
        }
        if (waited.exitCode() != 0) {
            throw new IOException("Podman wait failed for the tool attempt container.");
        }
        int exitCode;
        try {
            exitCode = Integer.parseInt(waited.output().trim());
        } catch (NumberFormatException e) {
            throw new IOException("Podman returned an invalid container exit status.", e);
        }
        String output = readContainerLogs(podmanPath, containerName);
        if (exitCode != 0) {
            throw new ToolExecutionException(output, exitCode);
        }
        return output.isBlank() ? "(no execution output)" : output;
    }

    static boolean releaseSettledAttempt(String podmanPath, String containerName, ToolExecutionContext context)
            throws Exception {
        Optional<ContainerInfo> inspected = inspectOwnedContainer(podmanPath, containerName, context);
        if (inspected.isEmpty()) {
            return true;
        }
        if ("running".equals(inspected.get().status())) {
            CommandResult stopped = runPodmanCommand(List.of(podmanPath, "stop", "--time", "5", containerName), 10);
            if (!stopped.finished() || stopped.exitCode() != 0) {
                return false;
            }
        }
        CommandResult removed = runPodmanCommand(List.of(podmanPath, "rm", containerName), 10);
        return removed.finished() && removed.exitCode() == 0;
    }

    private static String stopAndCollectOutput(String podmanPath, String containerName, ToolExecutionContext context)
            throws Exception {
        Optional<ContainerInfo> current = inspectOwnedContainer(podmanPath, containerName, context);
        if (current.isEmpty() || !"running".equals(current.get().status())) {
            return null;
        }
        CommandResult stopped = runPodmanCommand(List.of(podmanPath, "stop", "--time", "5", containerName), 10);
        if (!stopped.finished() || stopped.exitCode() != 0) {
            return null;
        }
        Optional<ContainerInfo> afterStop = inspectOwnedContainer(podmanPath, containerName, context);
        if (afterStop.isEmpty() || !"exited".equals(afterStop.get().status())) {
            return null;
        }
        return readContainerLogs(podmanPath, containerName);
    }

    private static String readContainerLogs(String podmanPath, String containerName) throws Exception {
        CommandResult logs = runPodmanCommand(List.of(podmanPath, "logs", containerName), 10);
        if (!logs.finished() || logs.exitCode() != 0) {
            throw new IOException("Unable to collect bounded output from the attempt container.");
        }
        return logs.output();
    }

    private static Optional<ContainerInfo> inspectOwnedContainer(
            String podmanPath, String containerName, ToolExecutionContext context) throws Exception {
        String template = "{{.Id}}|{{.State.Status}}|{{.State.StartedAt}}|"
                + "{{index .Config.Labels \"amsha.workspace-id\"}}|"
                + "{{index .Config.Labels \"amsha.tool-attempt-id\"}}";
        CommandResult result = runPodmanCommand(List.of(podmanPath, "inspect", "--format", template, containerName), 5);
        if (!result.finished() || result.exitCode() != 0) {
            return Optional.empty();
        }
        String[] values = result.output().trim().split("\\|", -1);
        if (values.length != 5
                || !encodeLabel(context.workspaceId()).equals(values[3])
                || !context.attemptId().equals(values[4])) {
            throw new IOException("Tool attempt container labels did not match the durable attempt.");
        }
        return Optional.of(new ContainerInfo(values[0], values[1], values[2]));
    }

    private static String encodeLabel(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static CommandResult runPodmanCommand(List<String> command, long timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readBoundedOutput(process));
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult("", -1, false);
        }
        return new CommandResult(output.get(5, TimeUnit.SECONDS), process.exitValue(), true);
    }

    private static String readBoundedOutput(Process process) {
        try (var input = process.getInputStream()) {
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                int available = Math.max(0, MAX_COMMAND_OUTPUT_BYTES - output.size());
                if (available > 0) {
                    output.write(buffer, 0, Math.min(count, available));
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException _) {
            return "";
        }
    }

    private record ContainerInfo(String id, String status, String startedAt) {}

    private record CommandResult(String output, int exitCode, boolean finished) {}
}
