package com.amsha.core.structured;

import com.amsha.core.exception.AmshaException;

/**
 * Thrown when the structured output retry loop fails after exhausting retries.
 */
public class StructuredOutputException extends AmshaException {
    public StructuredOutputException(String message) {
        super(message);
    }

    public StructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
