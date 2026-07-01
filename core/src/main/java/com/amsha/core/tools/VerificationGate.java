package com.amsha.core.tools;

public interface VerificationGate {

    /**
     * @param success true if verification passed successfully.
     * @param errorOutput null when success is true; error/failure details when success is false.
     * @nullable errorOutput
     */
    record VerificationResult(boolean success, String errorOutput) {}

    VerificationResult verify(ToolExecutionContext ctx, String output);
}
