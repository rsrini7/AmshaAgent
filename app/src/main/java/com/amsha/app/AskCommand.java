package com.amsha.app;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceScopeHolder;

/**
 * CLI one-shot mode - runs the agent reasoning loop for a single goal
 * and exits.
 * <p>
 * Usage: {@code amsha ask "Summarize the project" --role coordinator}
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

    @Option(
            names = {"--role", "-r"},
            defaultValue = AgentRoleEnum.COORDINATOR_ID,
            description = "Agent role (coordinator | coder | researcher | debugger)")
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
                    // Dev DX: seed host OS tokens into vault (no-op in prod - bean absent)
                    // Dev seeding handled by DevSecretSeederStartup (@IfBuildProfile("dev") - compile-time excluded
                    // from prod builds).

                    MessageContext context = createCliContext(sessionId, goal);
                    AgentResult result = agentLoop.run(sessionId, role, goal, reasoningEngine, 5, context);

                    String output =
                            switch (result) {
                                case AgentResult.Finished f -> f.answer();
                                case AgentResult.StructuredFinished sf -> sf.answer();
                                case AgentResult.Failed f -> "Error: " + f.reason();
                                case AgentResult.LoopExceeded l -> "Timed out after " + l.maxIterations() + " steps.";
                                case AgentResult.AwaitingApproval a ->
                                    "Agent requires approval to run tool: " + a.toolName();
                                case AgentResult.AwaitingRetry r ->
                                    "Agent rate-limited. Retrying in " + (r.retryAfterMs() / 1000) + "s...";
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
