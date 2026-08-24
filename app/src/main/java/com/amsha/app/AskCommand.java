package com.amsha.app;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.TurnDispatchResult;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.pipeline.TurnRequest;
import com.amsha.core.scope.WorkspaceScopeHolder;

/**
 * CLI one-shot mode - runs the agent reasoning loop for a single goal
 * and exits.
 * <p>
 * Usage: {@code amsha ask "Summarize the project" --role coordinator --verbose}
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
            description = "Explicit agent role. Omit to use shared intent and skill routing.")
    String role;

    @Option(names = "--verbose", description = "Print token usage and agent actions while running.")
    boolean verbose;

    @Inject
    TurnDispatchService turnDispatchService;

    @Inject
    WorkspaceManager workspaceManager;

    @Override
    @ActivateRequestContext
    public void run() {
        String sessionId = UUID.randomUUID().toString();
        try {
            WorkspaceScopeHolder.run(sessionId, () -> {
                ScopedValue.where(CliAgentObserver.VERBOSE, verbose).run(() -> {
                    // Dev DX: seed host OS tokens into vault (no-op in prod - bean absent)
                    // Dev seeding handled by DevSecretSeederStartup (@IfBuildProfile("dev") - compile-time excluded
                    // from prod builds).

                    ChannelMessage message = new ChannelMessage.CliMessage(
                            new ChannelUser("cli-user", "cli-chat", "CLI User", ChannelType.CLI),
                            goal,
                            sessionId,
                            Instant.now());
                    TurnDispatchResult result = turnDispatchService.dispatch(new TurnRequest(
                            message, "cli-chat", Optional.of(sessionId), Optional.ofNullable(role), Optional.empty()));
                    result.agentResult()
                            .filter(AgentResult.AwaitingApproval.class::isInstance)
                            .ifPresent(_ -> System.err.println(
                                    "Approval is required; resume this session through a supported interface."));
                });
            });
        } finally {
            workspaceManager.terminateSession(sessionId);
        }
    }
}
