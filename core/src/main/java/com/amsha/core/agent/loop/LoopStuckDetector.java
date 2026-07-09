package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Detects when the agent is stuck repeating non-progressing tool calls
 * and produces a dynamic corrective hint for the next LLM turn.
 * <p>
 * The detector is stateless and inspects only the recent reasoning history.
 * It avoids hardcoding skill-specific paths or tools; hints are derived from
 * the actual repeated tool input.
 */
@ApplicationScoped
public class LoopStuckDetector {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int IDENTICAL_REPEAT_THRESHOLD = 2;
    private static final int READ_ONLY_LOOP_THRESHOLD = 3;

    private static final Set<String> READ_COMMANDS = Set.of(
            "cat", "less", "more", "head", "tail", "grep", "find", "ls", "read", "get", "search", "awk", "sed", "wc",
            "file", "stat", "echo");

    private static final Set<String> WRITE_COMMANDS =
            Set.of("cp", "mv", "rm", "touch", "mkdir", "rmdir", "write", "create", "delete", "update", "insert");

    /**
     * Inspects the recent history and returns a corrective hint if the agent
     * appears to be stuck in a non-progressing loop.
     *
     * @param history   ordered reasoning steps, newest last
     * @param agentRole current role identifier
     * @return a hint to inject, or empty if no stuck pattern is detected
     */
    public Optional<String> detectStuckHint(List<ReasoningStep> history, String agentRole) {
        if (history == null || history.size() < IDENTICAL_REPEAT_THRESHOLD) {
            return Optional.empty();
        }

        ReasoningStep lastStep = history.get(history.size() - 1);
        if (lastStep instanceof ReasoningStep.FinishStep) {
            return Optional.empty();
        }

        List<ReasoningStep> toolCalls = history.stream()
                .filter(s -> s instanceof ReasoningStep.ToolCallStep)
                .filter(s -> !ReasoningStep.FINISH.equals(s.action()))
                .toList();

        if (toolCalls.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> identicalHint = detectIdenticalRepetition(toolCalls);
        if (identicalHint.isPresent()) {
            return identicalHint;
        }

        return detectReadOnlyLoop(toolCalls);
    }

    private static Optional<String> detectIdenticalRepetition(List<ReasoningStep> toolCalls) {
        int count = 1;
        ReasoningStep last = toolCalls.get(toolCalls.size() - 1);
        String lastSignature = signatureOf(last);

        for (int i = toolCalls.size() - 2; i >= 0; i--) {
            ReasoningStep current = toolCalls.get(i);
            if (signatureOf(current).equals(lastSignature)) {
                count++;
            } else {
                break;
            }
        }

        if (count < IDENTICAL_REPEAT_THRESHOLD) {
            return Optional.empty();
        }

        String toolName = last.action();
        String operationSummary = summarizeOperation(last.actionInput());

        return Optional.of("CRITICAL OVERRIDE — DO NOT REPEAT. You have already called `"
                + toolName + "` " + count + " times with the same input (" + operationSummary
                + "). This is NOT making progress. STOP calling this tool with the same arguments. "
                + "Use the information you already have, complete the task, and emit FINISH immediately. "
                + "If you cannot complete the task, emit FINISH explaining the blocker. "
                + "Do NOT call `" + toolName + "` again with the same arguments under any circumstances.");
    }

    private static Optional<String> detectReadOnlyLoop(List<ReasoningStep> toolCalls) {
        if (toolCalls.size() < READ_ONLY_LOOP_THRESHOLD) {
            return Optional.empty();
        }

        List<ReasoningStep> recent = toolCalls.subList(toolCalls.size() - READ_ONLY_LOOP_THRESHOLD, toolCalls.size());
        boolean allReadOnly = recent.stream().allMatch(LoopStuckDetector::isReadOnlyCall);

        if (!allReadOnly) {
            return Optional.empty();
        }

        return Optional.of("CRITICAL OVERRIDE — STOP INSPECTING AND START DOING. Your last "
                + READ_ONLY_LOOP_THRESHOLD
                + " tool calls were read-only operations. You already have enough information. "
                + "Perform the actual execution/write step required by the goal and emit FINISH immediately. "
                + "Do NOT issue another read/search/list command. If you cannot proceed, emit FINISH explaining why.");
    }

    private static boolean isReadOnlyCall(ReasoningStep step) {
        if (step instanceof ReasoningStep.FinishStep) {
            return false;
        }
        Map<String, Object> input = step.actionInput();
        if (input == null || input.isEmpty()) {
            return false;
        }
        String commandLike = extractCommandLikeString(input);
        if (commandLike == null || commandLike.isBlank()) {
            return false;
        }
        String command = commandLike.trim();

        // Any redirection or piping indicates side effects / transformation.
        if (command.contains(">") || command.contains("|")) {
            return false;
        }

        // Detect in-place sed edits (e.g., sed -i, sed --in-place, sed -i.bak).
        if (isInPlaceSed(command)) {
            return false;
        }

        String firstToken = extractFirstToken(command);
        // Strip common prefixes like "bash -c", "sh -c", "python -c".
        if (Set.of("bash", "sh", "zsh", "python", "python3").contains(firstToken.toLowerCase())) {
            String rest = command.substring(command.indexOf(' ') + 1).trim();
            if (rest.startsWith("-c ")) {
                rest = rest.substring(3).trim();
            }
            if (!rest.isEmpty()) {
                firstToken = extractFirstToken(rest);
            }
        }

        // Treat known write/delete/move verbs as non-read-only.
        if (isWriteVerb(firstToken)) {
            return false;
        }

        return READ_COMMANDS.contains(firstToken.toLowerCase());
    }

    private static String extractFirstToken(String command) {
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String token = trimmed.split("\\s+", 2)[0];
        // Strip surrounding quotes that may appear in wrapped commands.
        while ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
            if (token.length() < 2) {
                break;
            }
            token = token.substring(1, token.length() - 1);
        }
        return token;
    }

    private static boolean isInPlaceSed(String command) {
        String[] tokens = command.trim().split("\\s+");
        boolean sawSed = false;
        for (String token : tokens) {
            String stripped = extractFirstToken(token).toLowerCase();
            if ("sed".equals(stripped)) {
                sawSed = true;
                continue;
            }
            if (sawSed) {
                if (token.startsWith("-i") || token.startsWith("--in-place")) {
                    return true;
                }
                // Stop scanning once we pass the options block.
                if (!token.startsWith("-")) {
                    break;
                }
            }
        }
        return false;
    }

    private static boolean isWriteVerb(String token) {
        return WRITE_COMMANDS.contains(token.toLowerCase());
    }

    private static String summarizeOperation(Map<String, Object> actionInput) {
        if (actionInput == null || actionInput.isEmpty()) {
            return "no parameters";
        }

        String commandLike = extractCommandLikeString(actionInput);
        if (commandLike != null && !commandLike.isBlank()) {
            String cmd = commandLike.trim();
            String[] tokens = cmd.split("\\s+", 2);
            String first = tokens[0].toLowerCase();
            if (READ_COMMANDS.contains(first) && tokens.length > 1) {
                return "read command: `" + cmd + "`";
            }
            return "command: `" + cmd + "`";
        }

        return "parameters: " + actionInput.keySet();
    }

    private static String extractCommandLikeString(Map<String, Object> actionInput) {
        Object code = actionInput.get("code");
        if (code instanceof String s && !s.isBlank()) {
            return s;
        }
        Object command = actionInput.get("command");
        if (command instanceof String s && !s.isBlank()) {
            return s;
        }
        Object script = actionInput.get("script");
        if (script instanceof String s && !s.isBlank()) {
            return s;
        }
        Object query = actionInput.get("query");
        if (query instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static String signatureOf(ReasoningStep step) {
        Map<String, Object> input = step.actionInput();
        if (input == null) {
            input = Map.of();
        }
        try {
            // Normalize key order and JSON formatting for stable comparison.
            TreeMap<String, Object> sorted = new TreeMap<>(input);
            return step.action() + "|" + OBJECT_MAPPER.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            return step.action() + "|" + Objects.toString(input);
        }
    }
}
