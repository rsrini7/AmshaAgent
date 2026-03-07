package com.arcclaw.app;

import java.util.UUID;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.DevSecretSeeder;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI one-shot mode — runs the agent reasoning loop for a single goal
 * and exits.
 * <p>
 * Usage: {@code arcclaw ask "Summarize the project" --role coordinator}
 * <p>
 * <strong>CDI context:</strong> {@code @ActivateRequestContext} on
 * {@code run()} ensures CDI scopes are available on the picocli thread.
 * <p>
 * <strong>Session scoping:</strong> {@code sessionId} is declared before
 * the try block so it can be passed to
 * {@link WorkspaceManager#terminateSession(String)} in the finally block.
 */
@Command(name = "ask", description = "Run an agent task and exit")
public class AskCommand implements Runnable {

    @Parameters(index = "0", description = "The goal for the agent")
    String goal;

    @Option(names = { "--role", "-r" }, defaultValue = "coordinator", description = "Agent role (coordinator | coder | researcher | debugger)")
    String role;

    @Inject
    AgentLoop agentLoop;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    DevSecretSeeder devSeeder;

    @Override
    @ActivateRequestContext
    public void run() {
        String sessionId = UUID.randomUUID().toString();
        try {
            WorkspaceScopeHolder.run(sessionId, () -> {
                ScopedValue.where(CliAgentObserver.ENABLED, true).run(() -> {
                    // Dev DX: seed host OS tokens into vault (no-op in prod — bean absent)
                    devSeeder.seedDevSecrets(); // no-op when auto-seed-secrets=false

                    AgentResult result = agentLoop.run(sessionId, role, goal,
                            reasoningEngine, 5);

                    String output = switch (result) {
                        case AgentResult.Finished f -> f.answer();
                        case AgentResult.Failed f -> "Error: " + f.reason();
                        case AgentResult.LoopExceeded l -> "Timed out after " + l.maxIterations() + " steps.";
                        case AgentResult.AwaitingApproval a -> "Agent requires approval to run tool: " + a.toolName();
                    };
                    System.out.println("\n✅ Final Answer:\n" + output);
                });
            });
        } finally {
            workspaceManager.terminateSession(sessionId);
        }
    }

}
