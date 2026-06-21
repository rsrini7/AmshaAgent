package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.AgentGuards;

import org.jboss.logging.Logger;

public class PreflightState implements RunnerState {
    private static final Logger log = Logger.getLogger(PreflightState.class);

    private final RunnerServices services;

    public PreflightState(RunnerServices services) {
        this.services = services;
    }

    @Override
    public RunnerState execute(StateContext context) {
        if (!context.isBrowserPreflightChecked()) {
            context.setBrowserPreflightChecked(true);
            Optional<AgentResult> preflightResult =
                    services.browserPreflightHandler().run(context.executionContext(), context.history());
            if (preflightResult.isPresent()) {
                AgentResult res = preflightResult.get();
                context.setFinalResult(res);
                return services.terminalStateForResult(res);
            }
        }

        int iteration = context.iteration();
        AgentExecutionContext execCtx = context.executionContext();
        if (!(iteration < execCtx.maxIterations()
                || (iteration == execCtx.maxIterations() && execCtx.isForceFinishRetry()))) {
            context.setFinalResult(AgentResult.loopExceeded(execCtx.maxIterations(), List.copyOf(context.history())));
            return services.failedState();
        }

        if (WorkspaceManager.isSessionHalted(execCtx.sessionId())) {
            log.warnf("Session %s halted — stopping agent loop at iteration %d", execCtx.sessionId(), iteration);
            context.setFinalResult(AgentResult.failed(
                    "Session halted by user", AgentResult.FailureCategory.USER_ABORT, List.copyOf(context.history())));
            return services.failedState();
        }

        if (AgentGuards.isShuttingDown()) {
            log.warnf(
                    "Container shutting down — stopping agent loop for session %s at iteration %d",
                    execCtx.sessionId(), iteration);
            context.setFinalResult(AgentResult.failed(
                    "System shutting down", AgentResult.FailureCategory.USER_ABORT, List.copyOf(context.history())));
            return services.failedState();
        }

        if (!WorkspaceContext.incrementAndCheckGlobalIterations()) {
            log.warnf(
                    "Global iteration budget (%d) exceeded for session %s — terminating",
                    WorkspaceContext.DEFAULT_GLOBAL_MAX_ITERATIONS, execCtx.sessionId());
            // Mark session as halted so subsequent goal submissions are rejected
            // immediately instead of entering the loop and failing again.
            WorkspaceManager.haltSession(execCtx.sessionId());
            context.setFinalResult(AgentResult.loopExceeded(iteration, List.copyOf(context.history())));
            return services.failedState();
        }

        context.incrementIteration();
        return services.thinkingState();
    }

    @Override
    public boolean isTerminal() {
        return false;
    }
}
