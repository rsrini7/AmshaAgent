package com.amsha.core.sandbox;

public record SandboxResult(boolean success, String output, String reason, boolean isInfraFailure, boolean timedOut) {

    public SandboxResult(boolean success, String output, String reason, boolean isInfraFailure) {
        this(success, output, reason, isInfraFailure, false);
    }

    public static SandboxResult success(String output) {
        return new SandboxResult(true, output, null, false);
    }

    public static SandboxResult failure() {
        return new SandboxResult(
                false,
                SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE,
                SandboxFailureMessages.SANDBOX_EXECUTION_FAILED_MESSAGE,
                false);
    }

    public static SandboxResult failure(String rawOutput) {
        return failure();
    }

    public static SandboxResult infraFailure(String reason) {
        return new SandboxResult(false, "", SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE, true);
    }

    public static SandboxResult timedOut(String output) {
        return new SandboxResult(false, output == null ? "" : output, "Tool timed out.", false, true);
    }
}
