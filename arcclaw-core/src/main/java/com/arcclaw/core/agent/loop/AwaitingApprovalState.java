package com.arcclaw.core.agent.loop;

public class AwaitingApprovalState implements RunnerState {
    @Override
    public RunnerState execute(StateContext context) {
        return this;
    }

    @Override
    public boolean isTerminal() {
        return true;
    }
}
