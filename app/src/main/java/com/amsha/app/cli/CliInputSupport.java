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

    static final String APPROVE_COMMAND = "/approve";
    static final String DENY_COMMAND = "/deny";
    static final String EXIT_COMMAND = "/exit";
    static final String QUIT_COMMAND = "/quit";

    private CliInputSupport() {}

    static boolean isExitCommand(String input) {
        return EXIT_COMMAND.equalsIgnoreCase(input) || QUIT_COMMAND.equalsIgnoreCase(input);
    }

    static boolean isApprovalCommand(String input) {
        return input != null && (input.startsWith(APPROVE_COMMAND) || input.startsWith(DENY_COMMAND));
    }

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
            if (isExitCommand(input)) {
                System.out.println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;
            if (!isApprovalCommand(input) && hitlPending.getAsBoolean()) {
                System.out.println("\n▶ Amsha Agent> ⏸ Approval pending - type " + APPROVE_COMMAND + " or "
                        + DENY_COMMAND + " before sending new messages.");
                continue;
            }
            dispatch.accept(input);
        }
    }

    static List<String> slashCommands() {
        return List.of(
                APPROVE_COMMAND,
                "/approvals",
                "/commands",
                "/crons",
                "/deletecron",
                DENY_COMMAND,
                EXIT_COMMAND,
                "/halt",
                "/help",
                "/history",
                "/newsession",
                QUIT_COMMAND,
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
                + "\n   Type " + APPROVE_COMMAND + " or " + DENY_COMMAND
                + " (or approve via REST/UI - result will appear automatically)";
    }

    static String describePhase(String phase) {
        String label = ActivityPhase.label(phase);
        return label.isBlank() ? "" : " · " + label;
    }
}
