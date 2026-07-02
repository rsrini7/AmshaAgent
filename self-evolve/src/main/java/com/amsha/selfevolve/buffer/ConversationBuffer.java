package com.amsha.selfevolve.buffer;

import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.FlushReason;

/**
 * In-memory per-session accumulation buffer for evolution events.
 * Thread-safe — events may be emitted from multiple threads (virtual threads).
 */
public interface ConversationBuffer {

    /**
     * Appends an event to the buffer.
     *
     * @return true if the buffer reached the flush threshold after this append
     */
    boolean append(EvolutionEvent event);

    /**
     * Drains all accumulated events and resets the buffer.
     * Returns an empty list if buffer was already empty.
     */
    List<EvolutionEvent> drain(FlushReason reason);

    /**
     * Returns the current number of accumulated events without draining.
     */
    int size();

    /**
     * Returns true if the buffer has accumulated events.
     */
    boolean hasEvents();

    /**
     * Returns the session ID this buffer is bound to.
     */
    String sessionId();
}
