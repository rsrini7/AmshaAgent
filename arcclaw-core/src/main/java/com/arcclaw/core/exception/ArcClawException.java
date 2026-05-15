package com.arcclaw.core.exception;

public abstract class ArcClawException extends RuntimeException {

    protected ArcClawException(String message) {
        super(message);
    }

    protected ArcClawException(String message, Throwable cause) {
        super(message, cause);
    }
}
