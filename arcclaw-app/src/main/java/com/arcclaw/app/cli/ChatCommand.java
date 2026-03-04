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

        // NOT try-with-resources — never close Scanner(System.in)
        @SuppressWarnings("resource")
        Scanner scanner = new Scanner(System.in);

        try {
            if (!devSeeder.isUnsatisfied()) {
                devSeeder.get().seedDevSecrets();
            }

            try {
                WorkspaceContext.run(
                    sessionId, "cli-user", "cli-chat",
                    AutonomyLevel.FULL,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    () -> {
                        while (true) {
                            System.out.print("\nYou> ");
                            if (!scanner.hasNextLine()) break;
                            String input;
                            try {
                                input = scanner.nextLine().trim();
                            } catch (NoSuchElementException e) {
                                break;
                            }

                            if ("/exit".equalsIgnoreCase(input) 
                                    || "/quit".equalsIgnoreCase(input)) {
                                System.out.println("Goodbye.");
                                break;
                            }
                            if (input.isBlank()) continue;

                            // Route ALL input through reflex registry (handles /approve, /deny, 
                            // /reset, /history, /status AND PII sanitization for normal messages)
                            ReflexResult reflexResult = reflexRegistry.processIfReflex(
                                sessionId, "cli-user", "cli-chat", input);

                            if (reflexResult.commandResponse().isPresent()) {
                                // Reflex handled it — print and loop
                                String reflexResponse = reflexResult.commandResponse().get();
                                if (!reflexResponse.isBlank()) {
                                    System.out.println("\n▶ ArcClaw> " + reflexResponse);
                                }
                                continue;
                            }

                            // No reflex matched — use sanitized input for agent loop
                            String sanitizedInput = reflexResult.sanitizedInput();

                            try {
                                System.out.println("⚡ Thinking...");
                                AgentResult result = agentLoop.run(
                                    sessionId, role, sanitizedInput, reasoningEngine, maxIterations);

                                String output = switch (result) {
                                    case AgentResult.Finished f   -> f.answer();
                                    case AgentResult.Failed f     -> "❌ Error: " + f.reason();
                                    case AgentResult.LoopExceeded l -> "⏱ Timed out after " 
                                        + l.maxIterations() + " steps.";
                                    case AgentResult.AwaitingApproval a ->
                                        "⏸ Approval needed for tool: " + a.toolName() 
                                        + "\n   Type /approve or /deny";
                                };
                                System.out.println("\n▶ ArcClaw> " + output);

                            } catch (Exception e) {
                                System.err.println("❌ Error: " + e.getMessage());
                            }
                        }
                        return null;
                    });
            } catch (Exception e) {
                log.error("Error in chat command", e);
            }
        } finally {
            workspaceManager.terminateSession(sessionId);
        }

        System.out.println("\n[Terminated]");
    }
}