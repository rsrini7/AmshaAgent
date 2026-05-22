package com.arcclaw.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.loop.AnswerGuardStrategy.LeakedToolCall;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Parses raw tool-call JSON leaked into FINISH answers by small models
 * that cannot use the structured tool-calling API.
 *
 * <p>Supports four formats:
 * <ul>
 *   <li>NVIDIA TOOLCALLS: {@code TOOLCALLS toolname {"arg":"val"}}</li>
 *   <li>JSON object: {@code {"name":"tool","parameters":{...}}}</li>
 *   <li>JSON array: {@code {"tool_calls":[{"name":"tool","arguments":{...}}]}}</li>
 *   <li>Naked function call: {@code tool_name(arg="val", ...)}</li>
 * </ul>
 */
public class LeakedToolCallParser {

    private static final Logger log = Logger.getLogger(LeakedToolCallParser.class);

    private static final Pattern NVIDIA_TOOL_NAME_PATTERN =
            Pattern.compile("TOOLCALLS\\s+([a-zA-Z_][a-zA-Z0-9_]*)", Pattern.CASE_INSENSITIVE);

    private final ToolRegistry toolRegistry;
    private final Pattern nakedFunctionCallPattern;

    public LeakedToolCallParser(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        String toolNames = toolRegistry.listTools().stream().sorted().collect(Collectors.joining("|"));
        this.nakedFunctionCallPattern = Pattern.compile(
                "\\b(" + toolNames + ")\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
    }

    public Optional<LeakedToolCall> extractLeakedToolCall(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        log.debugf("Attempting to parse leaked tool call from answer (%d chars)", answer.length());

        // Try NVIDIA TOOLCALLS format
        Matcher nvidia = NVIDIA_TOOL_NAME_PATTERN.matcher(answer);
        if (nvidia.find()) {
            String toolName = nvidia.group(1);
            int jsonStart = answer.indexOf('{', nvidia.end());
            if (jsonStart >= 0) {
                Map<String, Object> args = parseJsonObject(answer.substring(jsonStart));
                if (args != null && toolRegistry.listTools().contains(toolName)) {
                    log.infof("Parsed NVIDIA TOOLCALLS format: tool=%s args=%d keys", toolName, args.size());
                    return Optional.of(new LeakedToolCall(toolName, args));
                }
            }
        }

        // Try JSON formats
        try {
            ObjectMapper mapper = new ObjectMapper();
            String trimmed = stripMarkdownFences(answer.strip());

            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                Map<String, Object> parsed = mapper.readValue(trimmed, new TypeReference<>() {});

                // Format: {"name":"tool","parameters":{...}}
                Optional<LeakedToolCall> fromObj = parseJsonObjectFormat(parsed);
                if (fromObj.isPresent()) {
                    log.infof("Parsed JSON object format: tool=%s", fromObj.get().toolName());
                    return fromObj;
                }

                // Format: {"tool_calls":[{"name":"tool","arguments":{...}}]}
                Optional<LeakedToolCall> fromArray = parseToolCallsArray(parsed);
                if (fromArray.isPresent()) {
                    log.infof("Parsed tool_calls array format: tool=%s", fromArray.get().toolName());
                    return fromArray;
                }

                log.debugf("JSON parsed but no known tool name found in keys: %s", parsed.keySet());
            }
        } catch (Exception e) {
            log.debugf("Not valid JSON for leaked tool call parse: %s", LogUtil.rootMessage(e));
        }

        // Try naked function call: tool_name(arg="val", ...)
        Optional<LeakedToolCall> naked = parseNakedFunctionCall(answer);
        if (naked.isPresent()) {
            log.infof("Parsed naked function call format: tool=%s", naked.get().toolName());
        } else {
            log.debugf("No parseable leaked tool call found in answer");
        }
        return naked;
    }

    private Optional<LeakedToolCall> parseJsonObjectFormat(Map<String, Object> parsed) {
        String name = extractToolName(parsed);
        if (name != null && toolRegistry.listTools().contains(name)) {
            Map<String, Object> params = extractParams(parsed);
            return Optional.of(new LeakedToolCall(name, params));
        }
        return Optional.empty();
    }

    private Optional<LeakedToolCall> parseToolCallsArray(Map<String, Object> parsed) {
        Object calls = parsed.get("tool_calls");
        if (calls instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> callMap) {
            Object nameObj = callMap.containsKey("name") ? callMap.get("name") : callMap.get("function");
            String callName = nameObj instanceof String s ? s : null;
            if (callName != null && toolRegistry.listTools().contains(callName)) {
                Object argsObj = callMap.containsKey("arguments") ? callMap.get("arguments") : callMap.get("parameters");
                @SuppressWarnings("unchecked")
                Map<String, Object> callArgs = argsObj instanceof Map ? (Map<String, Object>) argsObj : Map.of();
                return Optional.of(new LeakedToolCall(callName, callArgs));
            }
        }
        return Optional.empty();
    }

    private Optional<LeakedToolCall> parseNakedFunctionCall(String answer) {
        if (nakedFunctionCallPattern == null) return Optional.empty();
        Matcher naked = nakedFunctionCallPattern.matcher(answer);
        if (naked.find()) {
            String block = naked.group(0);
            int parenIdx = block.indexOf('(');
            if (parenIdx > 0) {
                String toolName = block.substring(0, parenIdx).trim();
                if (toolRegistry.listTools().contains(toolName)) {
                    return Optional.of(new LeakedToolCall(toolName, parseNakedArgs(block.substring(parenIdx))));
                }
            }
        }
        return Optional.empty();
    }

    private String extractToolName(Map<String, Object> parsed) {
        for (String key : List.of("name", "tool_name", "tool", "action", "function")) {
            Object val = parsed.get(key);
            if (val instanceof String s) return s;
        }
        return null;
    }

    private Map<String, Object> extractParams(Map<String, Object> parsed) {
        Object raw = parsed.get("parameters");
        if (raw == null) raw = parsed.get("arguments");
        if (raw == null) raw = parsed;
        if (!(raw instanceof Map<?, ?> m)) return Map.of();

        Map<String, Object> params = new HashMap<>();
        for (var entry : m.entrySet()) {
            if (entry.getKey() instanceof String k
                    && !List.of("name", "tool_name", "tool", "action", "function", "type", "parameters", "arguments").contains(k)) {
                params.put(k, entry.getValue());
            }
        }
        return params;
    }

    private static String stripMarkdownFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }

    private Map<String, Object> parseJsonObject(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseNakedArgs(String argsBlock) {
        Map<String, Object> args = new HashMap<>();
        Matcher m = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')").matcher(argsBlock);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            args.put(key, val);
        }
        return args;
    }
}
