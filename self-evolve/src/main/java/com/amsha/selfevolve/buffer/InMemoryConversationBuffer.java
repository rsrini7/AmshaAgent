package com.amsha.selfevolve.buffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.FlushReason;

/**
 * Thread-safe in-memory buffer using synchronized methods over ArrayList.
 */
public class InMemoryConversationBuffer implements ConversationBuffer {

    private final String sessionId;
    private final int maxEvents;
    private final List<EvolutionEvent> events = new ArrayList<>();

    /**
     * @param sessionId session this buffer is bound to
     * @param maxEvents flush threshold — buffer is "full" when size reaches this
     */
    public InMemoryConversationBuffer(String sessionId, int maxEvents) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be >= 1");
        }
        this.sessionId = sessionId;
        this.maxEvents = maxEvents;
    }

    @Override
    public synchronized boolean append(EvolutionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        events.add(event);
        return events.size() >= maxEvents;
    }

    @Override
    public synchronized List<EvolutionEvent> drain(FlushReason reason) {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<EvolutionEvent> snapshot = new ArrayList<>(events);
        events.clear();
        return snapshot;
    }

    @Override
    public synchronized void restore(List<EvolutionEvent> restoredEvents) {
        if (restoredEvents == null || restoredEvents.isEmpty()) {
            return;
        }
        List<EvolutionEvent> currentEvents = new ArrayList<>(events);
        events.clear();
        events.addAll(restoredEvents);
        events.addAll(currentEvents);
    }

    @Override
    public synchronized int size() {
        return events.size();
    }

    @Override
    public synchronized boolean hasEvents() {
        return !events.isEmpty();
    }

    @Override
    public String sessionId() {
        return sessionId;
    }
}
