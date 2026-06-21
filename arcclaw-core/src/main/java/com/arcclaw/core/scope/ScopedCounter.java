package com.arcclaw.core.scope;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple {@link WorkspaceScoped} test bean.
 * <p>
 * Uses an {@link AtomicInteger} counter so that concurrent threads sharing
 * the same session can safely increment and verify they see the same instance.
 */
@WorkspaceScoped
public class ScopedCounter {

    private final AtomicInteger count = new AtomicInteger(0);

    public ScopedCounter() {
        // Required no-arg constructor for CDI proxy generation.
    }

    /**
     * Increment and return the new count.
     */
    public int increment() {
        return count.incrementAndGet();
    }

    /**
     * Return the current count.
     */
    public int getCount() {
        return count.get();
    }
}
