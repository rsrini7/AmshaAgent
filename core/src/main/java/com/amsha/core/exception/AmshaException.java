package com.amsha.core.exception;

public abstract class AmshaException extends RuntimeException {

    protected AmshaException(String message) {
        super(message);
    }

    protected AmshaException(String message, Throwable cause) {
        super(message, cause);
    }
}
