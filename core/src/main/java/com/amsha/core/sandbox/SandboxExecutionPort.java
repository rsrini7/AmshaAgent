package com.amsha.core.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.amsha.tool.api.ToolExecutionContext;

/**
 * Decoupled SPI port for executing commands in a sandboxed isolation environment.
 */
public interface SandboxExecutionPort {

    String SANDBOX_OFFLINE_MESSAGE = SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE;

    /**
     * Executes a command within the isolated sandbox environment.
     *
     * @param sessionId the agent session ID
     * @param workspaceRoot the path to the workspace root directory
     * @param command the command and arguments to execute
     * @param requiredSecrets the map of secret names to values required for execution
     * @return the execution result
     */
    SandboxResult executeSandboxed(
            String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets);

    /** Executes a durable attempt with start evidence reported at the executor boundary. */
    default SandboxResult executeSandboxed(
            ToolExecutionContext context,
            Path workspaceRoot,
            List<String> command,
            Map<String, String> requiredSecrets,
            SandboxAttemptLifecycle lifecycle) {
        return executeSandboxed(context.sessionId(), workspaceRoot, command, requiredSecrets);
    }

    /** Releases attempt-owned external resources after terminal state is durably stored. */
    default void releaseSettledAttempt(ToolExecutionContext context) {}

    /**
     * Inspects the execution state of an active attempt to verify ownership and progress.
     */
    default SandboxRecoveryEvidence inspectAttempt(
            String workspaceId, String attemptId, String executionHandle, String processStartIdentity) {
        return new SandboxRecoveryEvidence.Inconclusive("Inspect not supported by this sandbox provider");
    }

    /**
     * Safely terminates a verified running attempt before recording an indeterminate settlement.
     * Returns true if the executor confirmed the attempt stopped or exited.
     */
    default boolean terminateRunningAttempt(
            String workspaceId, String attemptId, String executionHandle, String processStartIdentity) {
        return false;
    }
}
