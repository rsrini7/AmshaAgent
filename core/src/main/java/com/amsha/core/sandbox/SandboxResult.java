package com.amsha.core.sandbox;

public record SandboxResult(boolean success, String output, String reason, boolean isInfraFailure) {

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
}
