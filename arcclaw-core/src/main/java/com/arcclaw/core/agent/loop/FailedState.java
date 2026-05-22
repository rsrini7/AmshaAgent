package com.arcclaw.core.agent.loop;

/**
 * Represents any terminal state that is not a success (Finished) or paused (AwaitingApproval).
 * Covers both standard failures, loop-exceeded/budget-exceeded conditions, and other non-success outcomes.
 */
public class FailedState implements RunnerState {
    @Override
    public RunnerState execute(StateContext context) {
        return this;
    }

    @Override
    public boolean isTerminal() {
        return true;
    }
}
