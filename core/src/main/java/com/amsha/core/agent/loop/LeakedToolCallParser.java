package com.amsha.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.AnswerGuardStrategy.LeakedToolCall;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.ToolCallJsonKeys;
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

    /** DSML-style invoke blocks leaked into plain text answers. */
    private static final Pattern DSML_INVOKE_PATTERN =
            Pattern.compile("(?is)invoke\\s+name\\s*=\\s*\"([a-zA-Z_][a-zA-Z0-9_]*)\"");

    private static final Pattern DSML_PARAMETER_PATTERN =
            Pattern.compile("(?is)parameter\\s+name\\s*=\\s*\"([a-zA-Z_][a-zA-Z0-9_]*)\"[^>]*>(.*?)</[^>]*parameter>");

    /** Generic XML-style wrapper used by some models for tool calls. */
    private static final Pattern XML_FUNCTION_PATTERN =
            Pattern.compile("(?is)<function\\s*=\\s*\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?\\s*>");

    /** Generic XML wrapper with JSON body: <tool_call>{"name":"tool", ...}</tool_call>. */
    private static final Pattern XML_JSON_TOOL_CALL_PATTERN =
            Pattern.compile("(?is)<tool_call\\b[^>]*>\\s*(\\{.*?})\\s*</tool_call>");

    /** XML tool call format with separate tool_name and parameters tags. */
    private static final Pattern XML_TOOL_NAME_PARAMS_PATTERN = Pattern.compile(
            "(?is)<tool_call\\b[^>]*>\\s*<tool_name>([^<]+)</tool_name>\\s*<parameters>(.*?)</parameters>\\s*</tool_call>");

    private static final Pattern XML_PARAMETER_PATTERN = Pattern.compile(
            "(?is)<parameter(?:\\s+name\\s*=\\s*|\\s*=\\s*)\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?[^>]*>(.*?)</[^>]*parameter>");

    // Extracts content from markdown code blocks (```lang\n...\n```)
    private static final Pattern CODE_BLOCK_EXTRACT_PATTERN =
            Pattern.compile("```(?:\\w+)?\\s*\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private final ToolRegistry toolRegistry;
    private final Pattern nakedFunctionCallPattern;

    public LeakedToolCallParser(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        String toolNames = toolRegistry.listTools().stream().sorted().collect(Collectors.joining("|"));
        this.nakedFunctionCallPattern =
                Pattern.compile("\\b(" + toolNames + ")\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
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

        // Try DSML format
        Optional<LeakedToolCall> dsml = parseDsmlToolCall(answer);
        if (dsml.isPresent()) {
            log.infof("Parsed DSML format: tool=%s", dsml.get().toolName());
            return dsml;
        }

        // Try generic XML-style tool-call format
        Optional<LeakedToolCall> xml = parseXmlToolCall(answer);
        if (xml.isPresent()) {
            log.infof("Parsed XML tool-call format: tool=%s", xml.get().toolName());
            return xml;
        }

        Optional<LeakedToolCall> xmlJson = parseXmlJsonToolCall(answer);
        if (xmlJson.isPresent()) {
            log.infof("Parsed XML JSON tool-call format: tool=%s", xmlJson.get().toolName());
            return xmlJson;
        }

        Optional<LeakedToolCall> xmlToolNameParams = parseXmlToolNameParamsCall(answer);
        if (xmlToolNameParams.isPresent()) {
            log.infof(
                    "Parsed XML tool_name/parameters format: tool=%s",
                    xmlToolNameParams.get().toolName());
            return xmlToolNameParams;
        }

        // Try JSON formats
        try {
            ObjectMapper mapper = new ObjectMapper();
            String trimmed = stripMarkdownFences(answer.strip());

            // If the full text doesn't start with {, try extracting JSON from embedded code blocks
            if (!trimmed.startsWith("{")) {
                String fromBlock = extractJsonFromCodeBlock(answer);
                if (fromBlock != null) {
                    trimmed = fromBlock;
                }
            }

            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                Map<String, Object> parsed = mapper.readValue(trimmed, new TypeReference<>() {});

                // Format: {"name":"tool","parameters":{...}}
                Optional<LeakedToolCall> fromObj = parseJsonObjectFormat(parsed);
                if (fromObj.isPresent()) {
                    log.infof(
                            "Parsed JSON object format: tool=%s", fromObj.get().toolName());
                    return fromObj;
                }

                // Format: {"tool_calls":[{"name":"tool","arguments":{...}}]}
                Optional<LeakedToolCall> fromArray = parseToolCallsArray(parsed);
                if (fromArray.isPresent()) {
                    log.infof(
                            "Parsed tool_calls array format: tool=%s",
                            fromArray.get().toolName());
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

    private Optional<LeakedToolCall> parseDsmlToolCall(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();

        Matcher invoke = DSML_INVOKE_PATTERN.matcher(answer);
        if (!invoke.find()) {
            return Optional.empty();
        }

        String toolName = invoke.group(1);
        if (!toolRegistry.listTools().contains(toolName)) {
            return Optional.empty();
        }

        Map<String, Object> args = new HashMap<>();
        String tail = answer.substring(invoke.start());
        Matcher params = DSML_PARAMETER_PATTERN.matcher(tail);
        while (params.find()) {
            String key = params.group(1);
            String value = params.group(2) != null ? params.group(2).strip() : "";
            args.put(key, value);
        }
        return Optional.of(new LeakedToolCall(toolName, args));
    }

    private Optional<LeakedToolCall> parseXmlToolCall(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();

        Matcher function = XML_FUNCTION_PATTERN.matcher(answer);
        if (!function.find()) {
            return Optional.empty();
        }

        String toolName = function.group(1);
        if (!toolRegistry.listTools().contains(toolName)) {
            return Optional.empty();
        }

        Map<String, Object> args = new HashMap<>();
        String tail = answer.substring(function.start());
        Matcher params = XML_PARAMETER_PATTERN.matcher(tail);
        while (params.find()) {
            String key = params.group(1);
            String value = params.group(2) != null ? params.group(2).strip() : "";
            args.put(key, value);
        }
        return Optional.of(new LeakedToolCall(toolName, args));
    }

    private Optional<LeakedToolCall> parseXmlJsonToolCall(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();

        Matcher jsonBlock = XML_JSON_TOOL_CALL_PATTERN.matcher(answer);
        if (!jsonBlock.find()) {
            return Optional.empty();
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> parsed = mapper.readValue(jsonBlock.group(1), new TypeReference<>() {});
            return parseJsonObjectFormat(parsed);
        } catch (Exception e) {
            log.debugf("Failed to parse XML JSON tool-call body: %s", LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    private Optional<LeakedToolCall> parseXmlToolNameParamsCall(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();

        Matcher m = XML_TOOL_NAME_PARAMS_PATTERN.matcher(answer);
        if (!m.find()) {
            return Optional.empty();
        }

        String toolName = m.group(1).strip();
        if (!toolRegistry.listTools().contains(toolName)) {
            return Optional.empty();
        }

        String paramsContent = m.group(2).strip();
        Map<String, Object> args = new HashMap<>();
        if (paramsContent.startsWith("{") && paramsContent.endsWith("}")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                args = mapper.readValue(paramsContent, new TypeReference<>() {});
            } catch (Exception e) {
                log.debugf("Failed to parse JSON parameters in XML tool call: %s", LogUtil.rootMessage(e));
                return Optional.empty();
            }
        } else {
            // Try extracting via XML parameter tags
            Matcher paramsMatcher = XML_PARAMETER_PATTERN.matcher(paramsContent);
            while (paramsMatcher.find()) {
                String key = paramsMatcher.group(1);
                String value =
                        paramsMatcher.group(2) != null ? paramsMatcher.group(2).strip() : "";
                args.put(key, value);
            }
        }
        return Optional.of(new LeakedToolCall(toolName, args));
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
            Object nameObj = callMap.containsKey(ToolCallJsonKeys.NAME)
                    ? callMap.get(ToolCallJsonKeys.NAME)
                    : callMap.get(ToolCallJsonKeys.FUNCTION);
            String callName = nameObj instanceof String s ? s : null;
            if (callName != null && toolRegistry.listTools().contains(callName)) {
                Object argsObj = callMap.containsKey(ToolCallJsonKeys.ARGUMENTS)
                        ? callMap.get(ToolCallJsonKeys.ARGUMENTS)
                        : callMap.get(ToolCallJsonKeys.PARAMETERS);
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
        for (String key : List.of(
                ToolCallJsonKeys.NAME,
                ToolCallJsonKeys.TOOL_NAME,
                ToolCallJsonKeys.TOOL,
                ToolCallJsonKeys.ACTION,
                ToolCallJsonKeys.FUNCTION)) {
            Object val = parsed.get(key);
            if (val instanceof String s) return s;
        }
        return null;
    }

    private Map<String, Object> extractParams(Map<String, Object> parsed) {
        Object raw = parsed.get(ToolCallJsonKeys.PARAMETERS);
        if (raw == null) raw = parsed.get(ToolCallJsonKeys.ARGUMENTS);
        // Small models (e.g., Groq llama-4-scout) use "params" as the wrapper key
        if (raw == null) raw = parsed.get("params");
        if (raw == null) raw = parsed;
        if (!(raw instanceof Map<?, ?> m)) return Map.of();

        Map<String, Object> params = new HashMap<>();
        for (var entry : m.entrySet()) {
            if (entry.getKey() instanceof String k
                    && !List.of(
                                    ToolCallJsonKeys.NAME,
                                    ToolCallJsonKeys.TOOL_NAME,
                                    ToolCallJsonKeys.TOOL,
                                    ToolCallJsonKeys.ACTION,
                                    ToolCallJsonKeys.FUNCTION,
                                    "type",
                                    ToolCallJsonKeys.PARAMETERS,
                                    ToolCallJsonKeys.ARGUMENTS,
                                    "params")
                            .contains(k)) {
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

    /**
     * Extracts JSON from markdown code blocks embedded in text.
     * E.g., "some text\n```json\n{...}\n```\nmore text" → "{...}"
     * Returns the first JSON object found in a code block, or null.
     */
    static String extractJsonFromCodeBlock(String text) {
        Matcher m = CODE_BLOCK_EXTRACT_PATTERN.matcher(text);
        while (m.find()) {
            String candidate = m.group(1).strip();
            if (candidate.startsWith("{") && candidate.endsWith("}")) {
                return candidate;
            }
        }
        return null;
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
        // Pattern 1: key="value" or key='value' (traditional naked format)
        Matcher m = Pattern.compile(
                        "([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')")
                .matcher(argsBlock);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            args.put(key, val);
        }
        // Pattern 2: "key": "value" (JSON-style from Groq/other models)
        if (args.isEmpty()) {
            Matcher jm = Pattern.compile("\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(argsBlock);
            while (jm.find()) {
                args.put(jm.group(1), jm.group(2));
            }
        }
        return args;
    }
}
