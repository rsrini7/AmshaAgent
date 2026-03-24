package com.arcclaw.core.compaction;

import java.util.concurrent.atomic.AtomicBoolean;

import com.arcclaw.core.scope.WorkspaceScoped;

/**
 * A per-session flag that prevents concurrent compaction for the same session.
 * Managed by CDI within the WorkspaceScoped context.
 */
@WorkspaceScoped
public class CompactionInFlightFlag {
    private final AtomicBoolean flag = new AtomicBoolean(false);

    public boolean compareAndSet(boolean expect, boolean update) {
        return flag.compareAndSet(expect, update);
    }

    public void set(boolean value) {
        flag.set(value);
    }

    public boolean get() {
        return flag.get();
    }
}
