package com.amsha.core.compaction;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class CompactionInFlightFlag {

    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public boolean compareAndSet(String sessionId, boolean expect, boolean update) {
        return flags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).compareAndSet(expect, update);
    }

    public void set(String sessionId, boolean value) {
        flags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(value);
    }

    /** Called by SessionReaper to prevent map growth after session termination. */
    public void remove(String sessionId) {
        flags.remove(sessionId);
    }
}
