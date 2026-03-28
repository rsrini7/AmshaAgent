package com.arcclaw.core.tools;

/**
 * Immutable result from a tool execution.
 * <p>
 * Exit code 0 with {@code success=true} indicates clean execution.
 * Non-zero exit codes carry the tool's stderr/stdout in {@code output}.
 *
 * @param exitCode The process exit code (0 = success).
 * @param output   Combined stdout + stderr from the tool process.
 * @param success  Convenience flag: {@code exitCode == 0}.
 * @param isInfraFailure True if this is an infrastructure failure (e.g., sandbox offline).
 */
public record ToolResponse(int exitCode, String output, boolean success, boolean isInfraFailure) {

    /** Successful execution with output. */
    public static ToolResponse success(String output) {
        return new ToolResponse(0, output, true, false);
    }

    /** Failed execution with exit code and error output. */
    public static ToolResponse failure(int exitCode, String output) {
        return new ToolResponse(exitCode, output, false, false);
    }

    /** Infrastructure failure — sandbox/external system is offline. */
    public static ToolResponse infraFailure(String reason) {
        return new ToolResponse(1, reason, false, true);
    }
}
