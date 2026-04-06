package com.arcclaw.app;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
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

    @Override
    @ActivateRequestContext
    public void run() {
        String sessionId = UUID.randomUUID().toString();
        try {
            WorkspaceScopeHolder.run(sessionId, () -> {
                ScopedValue.where(CliAgentObserver.ENABLED, true).run(() -> {
                    // Dev DX: seed host OS tokens into vault (no-op in prod — bean absent)
                    // Dev seeding handled by DevSecretSeederStartup (@IfBuildProfile("dev") — compile-time excluded from prod builds).

                    MessageContext context = createCliContext(sessionId, goal);
                    AgentResult result = agentLoop.run(sessionId, role, goal,
                            reasoningEngine, 5, context);

                    String output = switch (result) {
                        case AgentResult.Finished f -> f.answer();
                        case AgentResult.StructuredFinished sf -> sf.answer();
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
    
    private MessageContext createCliContext(String sessionId, String goal) {
        ChannelUser user = new ChannelUser("cli-user", "cli-user", "CLI User", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, goal, sessionId, Instant.now());
        return new MessageContext("cli-chat", user, msg);
    }
}
