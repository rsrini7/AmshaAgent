package com.amsha.core.sandbox;

/**
 * Conclusive evidence gathered during startup recovery when inspecting an active attempt.
 * Per Proposal §§5.1, 7, and 12.1.
 */
public sealed interface SandboxRecoveryEvidence {

    /**
     * The referenced container or process is confirmed alive and owned by this attempt.
     */
    record RunningVerified(String executionHandle, String processStartIdentity) implements SandboxRecoveryEvidence {
        public RunningVerified {
            if (executionHandle == null || executionHandle.isBlank()) {
                throw new IllegalArgumentException("executionHandle is required");
            }
        }
    }

    /**
     * The referenced container or process exited and produced verified termination status and output.
     */
    record ExitedVerified(String executionHandle, String processStartIdentity, int exitCode, String output)
            implements SandboxRecoveryEvidence {
        public ExitedVerified {
            if (executionHandle == null || executionHandle.isBlank()) {
                throw new IllegalArgumentException("executionHandle is required");
            }
        }
    }

    /**
     * The referenced container or process does not exist on this host/executor.
     */
    record Missing(String reason) implements SandboxRecoveryEvidence {}

    /**
     * An entity exists with the specified handle or PID, but ownership verification failed
     * (e.g. mismatched labels, wrong container ID, or PID start time recycling).
     */
    record OwnershipMismatch(String reason) implements SandboxRecoveryEvidence {}

    /**
     * Evidence cannot be conclusively verified (e.g. executor offline, multi-node topology,
     * or unsupported execution handle format).
     */
    record Inconclusive(String reason) implements SandboxRecoveryEvidence {}
}
