package com.amsha.core.pipeline;

/**
 * Result of a message handler execution.
 */
public enum HandleResult {
    /**
     * The handler processed the message and the pipeline should continue to the next handler.
     */
    CONTINUE,

    /**
     * The handler processed the message and the pipeline should stop immediately.
     */
    STOP
}
