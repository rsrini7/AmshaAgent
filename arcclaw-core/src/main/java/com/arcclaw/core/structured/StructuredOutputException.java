package com.arcclaw.core.structured;

/**
 * Thrown when the structured output retry loop fails after exhausting retries.
 */
public class StructuredOutputException extends RuntimeException {
    public StructuredOutputException(String message) {
        super(message);
    }

    public StructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
