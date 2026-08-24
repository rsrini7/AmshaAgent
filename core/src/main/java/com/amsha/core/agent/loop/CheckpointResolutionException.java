package com.amsha.core.agent.loop;

import com.amsha.common.exception.AmshaException;

/**
 * Thrown when a HITL checkpoint resolution (approve/deny) fails due to
 * state mismatch or missing records.
 */
public class CheckpointResolutionException extends AmshaException {

    private final int statusCode;

    public CheckpointResolutionException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
