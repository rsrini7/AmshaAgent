package com.arcclaw.core.sandbox;

public record SandboxResult(boolean success, String output, String reason, boolean isInfraFailure) {

    public static SandboxResult success(String output) {
        return new SandboxResult(true, output, null, false);
    }

    public static SandboxResult failure(String reason) {
        return new SandboxResult(false, "", reason, false);
    }

    public static SandboxResult infraFailure(String reason) {
        return new SandboxResult(false, "", reason, true);
    }
}
