package com.arcclaw.core.util;

import io.quarkus.arc.Arc;

public final class AgentGuards {
    private AgentGuards() {}

    public static boolean isContainerAvailable() {
        var c = Arc.container();
        return c != null && c.isRunning();
    }
}