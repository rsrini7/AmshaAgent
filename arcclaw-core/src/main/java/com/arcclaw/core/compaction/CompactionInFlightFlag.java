package com.arcclaw.core.compaction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CompactionInFlightFlag {

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public boolean compareAndSet(String sessionId, boolean expect, boolean update) {
        return flags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false))
                    .compareAndSet(expect, update);
    }

    public void set(String sessionId, boolean value) {
        flags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(value);
    }

    /** Called by SessionReaper to prevent map growth after session termination. */
    public void remove(String sessionId) {
        flags.remove(sessionId);
    }
}