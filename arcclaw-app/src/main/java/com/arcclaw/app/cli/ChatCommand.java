package com.arcclaw.app.cli;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.CronTimeResolver;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import picocli.CommandLine;

@CommandLine.Command(name = "chat", description = "Start an interactive chat session with ArcClaw")
public class ChatCommand implements Runnable {

    @CommandLine.Option(names = {"-r", "--role"}, defaultValue = "coordinator")
    String role = "coordinator";

    @Inject Logger log;
    @Inject AgentLoop agentLoop;
    @Inject ReasoningEngine reasoningEngine;
    @Inject WorkspaceManager workspaceManager;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject ReflexRegistry reflexRegistry;
    @Inject StatefulAgentStore statefulAgentStore;
    @Inject CliResultBridge cliResultBridge;

    private volatile String currentCliSessionId;

    @ConfigProperty(name = "arcclaw.agent.max-iterations", defaultValue = "5")
    int maxIterations;

    @ConfigProperty(name = "arcclaw.agent.default-timezone", defaultValue = "Asia/Kolkata")
    String defaultTimezone;

    @Override
    @ActivateRequestContext
    public void run() {
        String sessionId = "cli-" + UUID.randomUUID();
        this.currentCliSessionId = sessionId;

        System.out.println("✦ ArcClaw Interactive Terminal");
        System.out.println("   Session : " + sessionId);
        System.out.println("   Role    : " + role);
        System.out.println("   Commands: /exit to quit, /reset to start fresh");
        System.out.println("-------------------------------------------------");

        try {
            ScopedValue.where(WorkspaceContext.CHANNEL_TYPE, ChannelType.CLI.id()).run(
                () -> runChatLoop(sessionId)
            );
        } catch (Exception e) {
            log.errorf(e, "Fatal error in chat session %s", sessionId);
            System.err.println("❌ Fatal: " + e.getMessage());
        } finally {
            if (this.currentCliSessionId != null) {
                this.currentCliSessionId = null;
                workspaceManager.terminateSession(sessionId);
            }
        }

        System.out.println("\n[Terminated]");
    }

    /**
     * Prioritized shutdown observer to ensure session cleanup happens BEFORE
     * the Hibernate entity manager and connection pools are closed by Quarkus.
     */
    @ActivateRequestContext
    void onShutdown(@Observes @Priority(Integer.MIN_VALUE) ShutdownEvent event) {
        String sessionId = this.currentCliSessionId;
        if (sessionId == null) return;
        this.currentCliSessionId = null; // Prevent run() from calling it again

        try {
            log.debugf("CLI ShutdownEvent triggered for session %s (absolute priority cleanup)", sessionId);
            cliResultBridge.cancel(sessionId);
            workspaceManager.terminateSession(sessionId);
        } catch (Exception e) {
            // WorkspaceManager.markCheckpointsFailed now also guards with a catch(IllegalStateException)
            // just in case this still runs late, but @Priority(Integer.MIN_VALUE) should prevent that.
            log.warnf("Session cleanup on shutdown incomplete for %s: %s", sessionId, e.getMessage());
        }
    }

    private void runChatLoop(String sessionId) {
        try {
            heartbeatTracker.recordHeartbeat(sessionId);
            WorkspaceContext.run(
                sessionId, "cli-user", "cli-chat",
                AutonomyLevel.SUPERVISED,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                () -> {
                    
                    // Seeding handled by DevSecretSeederStartup (build-profile guarded)
                    chatLoop(sessionId);
                    return null;
                }
            );
        } catch (Exception e) {
            log.errorf(e, "Error in WorkspaceContext for session %s", sessionId);
            System.err.println("❌ Session error: " + e.getMessage());
        }
    }

    @SuppressWarnings("resource")
    private void chatLoop(String sessionId) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nYou> ");
            if (!scanner.hasNextLine()) break;

            String input;
            try {
                input = scanner.nextLine().trim();
            } catch (NoSuchElementException _) {
                break;
            }

            if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                System.out.println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;

            if (!input.startsWith("/approve") && !input.startsWith("/deny")) {
                if (statefulAgentStore.findLatestAwaitingApproval(sessionId).isPresent()) {
                    // Defensive re-register in case session was lost (e.g. CLI crash/restart)
                    cliResultBridge.register(sessionId);
                    System.out.println("\n▶ ArcClaw> ⏸ Approval pending — type /approve or /deny before sending new messages.");
                    continue;
                }
            }

            heartbeatTracker.recordHeartbeat(sessionId);

            MessageContext context = createCliContext(sessionId, input);

            // Pre-compute scheduling metadata for CLI (Issue 1)
            ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
            ZoneId userZone = ZoneId.of(defaultTimezone);
            ZonedDateTime targetUtc = CronTimeResolver.resolveTargetTime(input, nowUtc, userZone);
            if (targetUtc != null) {
                context.setData("SUGGESTED_CRON", String.format("%d %d * * *", 
                        targetUtc.getMinute(), targetUtc.getHour()));
                context.setData("TARGET_UTC_MINUTE", targetUtc.getMinute());
                context.setData("TARGET_UTC_HOUR", targetUtc.getHour());
                context.setData("SUGGESTED_NEXT_FIRE_TIME", 
                        targetUtc.toInstant().toString());
                context.setData("CURRENT_LOCAL_TIME", 
                        ZonedDateTime.now(userZone).format(DateTimeFormatter.ofPattern("HH:mm z")));
            }

            ReflexResult reflexResult = reflexRegistry.processIfReflex(
                sessionId, "cli-user", "cli-chat", input, context);

            if (reflexResult.commandResponse().isPresent()) {
                String response = reflexResult.commandResponse().get();
                if (!response.isBlank()) {
                    System.out.println("\n▶ ArcClaw> " + response);
                }
                continue;
            }

            dispatchToAgent(sessionId, reflexResult.sanitizedInput(), context);
        }
    }
    private void dispatchToAgent(String sessionId, String input, MessageContext context) {
        try {
            System.out.println("⚡ Thinking...");
            cliResultBridge.register(sessionId);
            AgentResult result = agentLoop.run(
                sessionId, role, input, reasoningEngine, maxIterations, context);

            String output = switch (result) {
                case AgentResult.Finished f      -> f.answer();
                case AgentResult.StructuredFinished sf -> sf.answer();
                case AgentResult.Failed f        -> "❌ Error: " + f.reason();
                case AgentResult.LoopExceeded l  -> "⏱ Timed out after " + l.maxIterations() + " steps.";
                case AgentResult.AwaitingApproval a -> {
                    heartbeatTracker.recordHeartbeat(sessionId);
                    String truncatedParams = a.paramsJson() != null
                            && a.paramsJson().length() > 200
                            ? a.paramsJson().substring(0, 200) + "... [truncated]"
                            : a.paramsJson();

                    System.out.println("\n▶ ArcClaw> ⏸ Approval needed for tool: " + a.toolName() +
                            "\n   Params: " + truncatedParams +
                            "\n   Type /approve or /deny");
                    yield null;
                }
                case AgentResult.AwaitingRetry r -> "⏳ Rate limit hit. I'll automatically retry in " + (r.retryAfterMs() / 1000) + "s...";
            };

            // Clean up registration unless we are actively blocking for HITL approval.
            // If we are awaiting approval, we KEEP the registration so REST signals can wake us
            // during the next You> prompt or a future /approve call.
            if (!(result instanceof AgentResult.AwaitingApproval)) {
                cliResultBridge.cancel(sessionId);
            }

            if (output != null) {
                System.out.println("\n▶ ArcClaw> " + output);
            }

        } catch (Exception e) {
            log.errorf(e, "Agent error in session %s", sessionId);
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private MessageContext createCliContext(String sessionId, String input) {
        ChannelUser user = new ChannelUser("cli-user", "cli-user", "CLI User", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, sessionId, Instant.now());
        return new MessageContext("cli-chat", user, msg);
    }
}

