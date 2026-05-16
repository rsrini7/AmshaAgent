package com.arcclaw.core.sandbox;

import com.arcclaw.core.exception.ArcClawException;

public class ToolExecutionException extends ArcClawException {
    private final int exitCode;

    public ToolExecutionException(String message) {
        super(message);
        this.exitCode = -1;
    }

    public ToolExecutionException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
