package com.amsha.sandbox;

/** Timeout reported after the owned sandbox container was stopped and its output collected. */
final class ConfirmedSandboxTimeoutException extends Exception {
    private final String output;

    ConfirmedSandboxTimeoutException(String output) {
        super("Sandbox command timed out after confirmed termination.");
        this.output = output;
    }

    String output() {
        return output;
    }
}
