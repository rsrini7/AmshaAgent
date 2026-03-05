package com.arcclaw.app.cli;

import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;

import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import picocli.CommandLine;

@CommandLine.Command(name = "chat", description = "Start an interactive chat session with ArcClaw")
public class ChatCommand implements Runnable {

    @CommandLine.Option(names = {"-r", "--role"}, defaultValue = "coordinator")
    String role;

    @Inject Logger log;
    @Inject AgentLoop agentLoop;
    @Inject ReasoningEngine reasoningEngine;
    @Inject WorkspaceManager workspaceManager;
    @Inject ReflexRegistry reflexRegistry;
    @Inject Instance<DevSecretSeeder> devSeeder;

    @ConfigProperty(name = "arcclaw.agent.max-iterations", defaultValue = "5")
    int maxIterations;

    @Override
    @ActivateRequestContext
    public void run() {
        String sessionId = "cli-" + UUID.randomUUID();

        System.out.println("✦ ArcClaw Interactive Terminal");
        System.out.println("   Session : " + sessionId);
        System.out.println("   Role    : " + role);
        System.out.println("   Commands: /exit to quit, /reset to start fresh");
        System.out.println("-------------------------------------------------");

        try {
            if (!devSeeder.isUnsatisfied()) {
                devSeeder.get().seedDevSecrets();
            }
            ScopedValue.where(WorkspaceContext.CHANNEL_TYPE, "CLI").run(
                () -> runChatLoop(sessionId)
            );
        } catch (Exception e) {
            log.errorf(e, "Fatal error in chat session %s", sessionId);
            System.err.println("❌ Fatal: " + e.getMessage());
        } finally {
            workspaceManager.terminateSession(sessionId);
        }

        System.out.println("\n[Terminated]");
    }

    private void runChatLoop(String sessionId) {
        try {
            WorkspaceContext.run(
                sessionId, "cli-user", "cli-chat",
                AutonomyLevel.FULL,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                () -> chatLoop(sessionId)
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
            } catch (NoSuchElementException e) {
                break;
            }

            if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                System.out.println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;

            ReflexResult reflexResult = reflexRegistry.processIfReflex(
                sessionId, "cli-user", "cli-chat", input);

            if (reflexResult.commandResponse().isPresent()) {
                String response = reflexResult.commandResponse().get();
                if (!response.isBlank()) {
                    System.out.println("\n▶ ArcClaw> " + response);
                }
                continue;
            }

            dispatchToAgent(sessionId, reflexResult.sanitizedInput());
        }
    }

    private void dispatchToAgent(String sessionId, String input) {
        try {
            System.out.println("⚡ Thinking...");
            AgentResult result = agentLoop.run(
                sessionId, role, input, reasoningEngine, maxIterations);

            String output = switch (result) {
                case AgentResult.Finished f      -> f.answer();
                case AgentResult.Failed f        -> "❌ Error: " + f.reason();
                case AgentResult.LoopExceeded l  -> "⏱ Timed out after " + l.maxIterations() + " steps.";
                case AgentResult.AwaitingApproval a -> 
                    "⏸ Approval needed for tool: " + a.toolName() + "\n   Type /approve or /deny";
            };
            System.out.println("\n▶ ArcClaw> " + output);

        } catch (Exception e) {
            log.errorf(e, "Agent error in session %s", sessionId);
            System.err.println("❌ Error: " + e.getMessage());
        }
    }
}