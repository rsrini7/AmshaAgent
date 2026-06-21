package com.amsha.app.config;

/**
 * Thrown when the native binary's baked-in persistence backend
 * does not match the runtime {@code AMSHA_PERSISTENCE_BACKEND} config.
 */
public class BackendMismatchException extends RuntimeException {
    public BackendMismatchException(String message) {
        super(message);
    }
}
