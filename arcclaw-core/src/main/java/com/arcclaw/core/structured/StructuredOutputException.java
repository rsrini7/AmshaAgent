package com.arcclaw.core.structured;

import com.arcclaw.core.exception.ArcClawException;

/**
 * Thrown when the structured output retry loop fails after exhausting retries.
 */
public class StructuredOutputException extends ArcClawException {
    public StructuredOutputException(String message) {
        super(message);
    }

    public StructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
