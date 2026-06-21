package com.amsha.core.governance;

import com.amsha.core.exception.AmshaException;

/**
 * Thrown when a workspace session exceeds its allowed USD or token budget
 * during an agent reasoning loop.
 */
public class BudgetExceededException extends AmshaException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
