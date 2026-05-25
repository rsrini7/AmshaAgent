package com.arcclaw.app.config;

/**
 * Thrown when the native binary's baked-in persistence backend
 * does not match the runtime {@code ARCCLAW_PERSISTENCE_BACKEND} config.
 */
public class BackendMismatchException extends RuntimeException {
    public BackendMismatchException(String message) {
        super(message);
    }
}
