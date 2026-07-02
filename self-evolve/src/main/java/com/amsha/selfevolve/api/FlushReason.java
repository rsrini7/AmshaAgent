package com.amsha.selfevolve.api;

/**
 * Reason why the conversation buffer was flushed to persistent storage.
 */
public enum FlushReason {
    SESSION_END,
    EVENT_THRESHOLD,
    IDLE_TIMEOUT,
    EXPLICIT
}
