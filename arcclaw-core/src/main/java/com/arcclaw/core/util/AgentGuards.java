package com.arcclaw.core.util;

import io.quarkus.arc.Arc;

public final class AgentGuards {
    private AgentGuards() {}

    /**
     * Returns true if the CDI container exists and is in the process of shutting down.
     * Returns false if the container is running OR if it doesn't exist (e.g. in unit tests).
     */
    public static boolean isShuttingDown() {
        var c = Arc.container();
        return c != null && !c.isRunning();
    }
}