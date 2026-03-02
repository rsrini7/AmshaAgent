package com.arcclaw.app.cli;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.Scanner;
import java.util.UUID;

@CommandLine.Command(name = "chat", description = "Start an interactive chat session with ArcClaw")
public class ChatCommand implements Runnable {

    @CommandLine.Option(names = { "-s", "--session" }, description = "Session ID to resume an existing conversation")
    String sessionId;

    @CommandLine.Option(names = { "-r", "--role" }, defaultValue = "coordinator", 
            description = "Agent role (coordinator | coder | researcher | debugger)")
    String role;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Override
    @ActivateRequestContext
    public void run() {
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
        }

        System.out.println("✦ ArcClaw Interactive Terminal");
        System.out.println("   Session : " + sessionId);
        System.out.println("   Role    : " + role);
        System.out.println("   Commands: /exit to quit, /reset to start fresh");
        System.out.println("-------------------------------------------------");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nYou> ");
                String input;
                try {
                    if (!scanner.hasNextLine())
                        break;
                    input = scanner.nextLine();
                } catch (java.util.NoSuchElementException e) {
                    // Handle Ctrl+C or stream closure
                    break;
                }

                if ("/exit".equalsIgnoreCase(input.trim()) ||
                        "/quit".equalsIgnoreCase(input.trim())) {
                    System.out.println("Goodbye.");
                    break;
                }
                if ("/reset".equalsIgnoreCase(input.trim())) {
                    sessionId = UUID.randomUUID().toString();
                    System.out.println("↩ New session: " + sessionId);
                    continue;
                }
                if (input.trim().isEmpty())
                    continue;

                try {
                    System.out.println("⚡ Thinking...");
                    AgentResult result = agentLoop.run(
                            sessionId, role, input, reasoningEngine, 5);
                            
                    String output = switch (result) {
                        case AgentResult.Finished f -> f.answer();
                        case AgentResult.Failed f -> "Error: " + f.reason();
                        case AgentResult.LoopExceeded l -> "Timed out after " + l.maxIterations() + " steps.";
                        case AgentResult.AwaitingApproval a -> "Agent requires approval to run tool: " + a.toolName();
                    };
                    System.out.println("\n▶ ArcClaw> " + output);

                    if (result instanceof AgentResult.AwaitingApproval) {
                        System.out.println(
                                "\n⚠️  Type /approve or /deny to continue.");
                        // Handle approve/deny inline for CLI mode
                        // (approval resume re-uses same sessionId on next run)
                        System.out.println(
                                "/approve and /deny are handled on next message. " +
                                        "Type /approve as your next message.");
                    }
                } catch (Exception e) {
                    System.err.println("❌ Error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // General safety net
        }
        System.out.println("\n[Terminated]");
    }
}
