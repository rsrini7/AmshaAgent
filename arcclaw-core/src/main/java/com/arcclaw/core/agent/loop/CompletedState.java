package com.arcclaw.core.agent.loop;

public class CompletedState implements RunnerState {
    @Override
    public RunnerState execute(StateContext context) {
        return this;
    }

    @Override
    public boolean isTerminal() {
        return true;
    }
}
