package com.amsha.sandbox;

import com.amsha.common.exception.AmshaException;

public class ToolExecutionException extends AmshaException {
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
