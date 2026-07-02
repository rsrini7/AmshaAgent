package com.amsha.selfevolve.runtime;

/**
 * Policy that determines when a conversation buffer should be flushed.
 * Stateless — all state lives in the buffer and service.
 */
public class FlushTriggerPolicy {

    private final int maxEvents;
    private final long idleMs;

    /**
     * @param maxEvents flush when buffer reaches this size (0 = never on count)
     * @param idleMs    flush when no events for this many milliseconds (0 = never on idle)
     */
    public FlushTriggerPolicy(int maxEvents, long idleMs) {
        this.maxEvents = maxEvents;
        this.idleMs = idleMs;
    }

    /**
     * Called after an event is appended. Returns true if the buffer should be flushed now.
     */
    public boolean shouldFlushOnAppend(int currentSize) {
        return maxEvents > 0 && currentSize >= maxEvents;
    }

    /**
     * Called by the idle timer. Returns true if the buffer should be flushed.
     *
     * @param lastEventTimestamp timestamp of the last event in the buffer (epoch millis)
     * @param now                current timestamp (epoch millis)
     * @param hasEvents          whether the buffer has events
     */
    public boolean shouldFlushOnIdle(long lastEventTimestamp, long now, boolean hasEvents) {
        if (!hasEvents || idleMs <= 0) {
            return false;
        }
        return (now - lastEventTimestamp) >= idleMs;
    }

    public int maxEvents() {
        return maxEvents;
    }

    public long idleMs() {
        return idleMs;
    }
}
