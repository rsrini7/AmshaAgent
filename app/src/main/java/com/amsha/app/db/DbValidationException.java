package com.amsha.app.db;

/**
 * Thrown when a database console query fails fail-closed safety validation.
 */
public class DbValidationException extends RuntimeException {

    public DbValidationException(String message) {
        super(message);
    }
}
