package com.arcclaw.core.governance;

/**
 * Thrown when a workspace session exceeds its allowed USD or token budget
 * during an agent reasoning loop.
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
