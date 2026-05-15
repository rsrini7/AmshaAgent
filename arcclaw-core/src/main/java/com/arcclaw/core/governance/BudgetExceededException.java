package com.arcclaw.core.governance;

import com.arcclaw.core.exception.ArcClawException;

/**
 * Thrown when a workspace session exceeds its allowed USD or token budget
 * during an agent reasoning loop.
 */
public class BudgetExceededException extends ArcClawException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
