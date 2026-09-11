package com.amsha.app.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.state.ActivityPhase;

/** Small, stateless helpers for the interactive CLI input and presentation paths. */
final class CliInputSupport {

    private CliInputSupport() {}

    static void runFallback(BooleanSupplier hitlPending, Consumer<String> dispatch) {
        Console console = System.console();
        BufferedReader fallbackReader =
                console == null ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
        while (true) {
            String input;
            try {
                if (console != null) {
                    input = console.readLine("\nYou> ");
                } else {
                    System.out.print("\nYou> ");
                    System.out.flush();
                    input = fallbackReader.readLine();
                }
                if (input == null) break;
                input = input.trim();
            } catch (Exception _) {
                break;
            }
            if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                System.out.println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;
            if (!input.startsWith("/approve") && !input.startsWith("/deny") && hitlPending.getAsBoolean()) {
                System.out.println(
                        "\n▶ Amsha Agent> ⏸ Approval pending - type /approve or /deny before sending new messages.");
                continue;
            }
            dispatch.accept(input);
        }
    }

    static List<String> slashCommands() {
        return List.of(
                "/approve",
                "/approvals",
                "/commands",
                "/crons",
                "/deletecron",
                "/deny",
                "/exit",
                "/halt",
                "/help",
                "/history",
                "/newsession",
                "/quit",
                "/reset",
                "/resume",
                "/retry",
                "/sessions",
                "/status",
                "/store",
                "/vault");
    }

    static String formatApprovalPrompt(AgentResult.AwaitingApproval awaiting) {
        String params = awaiting.displayParamsJson();
        if (params != null && params.length() > 200) params = params.substring(0, 200) + "... [truncated]";
        return "\n▶ Amsha Agent> ⏸ Approval needed for tool: " + awaiting.toolName()
                + "\n   Params: " + (params == null || params.isBlank() ? "(none)" : params)
                + "\n   Type /approve or /deny (or approve via REST/UI - result will appear automatically)";
    }

    static String describePhase(String phase) {
        String label = ActivityPhase.label(phase);
        return label.isBlank() ? "" : " · " + label;
    }
}
