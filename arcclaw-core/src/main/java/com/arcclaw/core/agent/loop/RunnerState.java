package com.arcclaw.core.agent.loop;

interface RunnerState {
    RunnerState execute(StateContext context);
    boolean isTerminal();

    /**
     * Indicates whether the state is part of the logical iteration span.
     * One logical iteration covers both the engine thought (ThinkingState) and the subsequent
     * tool execution/routing (ToolExecutionState) under a single OpenTelemetry span,
     * matching the old processSingleIteration behavior.
     */
    default boolean isInsideIterationSpan() {
        return false;
    }
}

