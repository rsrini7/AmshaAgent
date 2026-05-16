package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.util.TextPatternUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

/**
 * Parses the ChatResponse from LangChain4j into a ReasoningStep.
 */
@ApplicationScoped
public class ReasoningStepParser {

    private static final Logger log = Logger.getLogger(ReasoningStepParser.class);

    /** Strips <think>...</think> blocks from Qwen/DeepSeek-style thinking models. */
    private static final Pattern THINKING_TAG_PATTERN =
            Pattern.compile("(?s)<think>.*?</think>\\s*", Pattern.DOTALL);

    private static final Pattern LANGUAGE_FIELD_PATTERN =
            Pattern.compile("\"language\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RELATIVE_SCHEDULE_PATTERN =
            Pattern.compile("\\b(in\\s+\\d+\\s*(min|mins|minute|minutes|hour|hours)|in\\s+one\\s+(min|minute|hour))\\b");
    private static final Pattern ABSOLUTE_SCHEDULE_PATTERN =
            Pattern.compile("\\b(at\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?|every\\s+(day|hour|minute|week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b");

    private final ObjectMapper objectMapper;
    private final TextPatternUtils textPatternUtils;

    protected ReasoningStepParser() {
        this.objectMapper = null;
        this.textPatternUtils = null;
    }

    @Inject
    public ReasoningStepParser(ObjectMapper objectMapper, TextPatternUtils textPatternUtils) {
        this.objectMapper = objectMapper;
        this.textPatternUtils = textPatternUtils;
    }

    public ReasoningStep parse(String sessionId, String agentRole, ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (requests.size() == 1) {
                return handleSingleToolCall(sessionId, aiMessage, requests.getFirst(), inputTokens, outputTokens);
            }
            if (requests.size() > 1) {
                return handleMultipleToolCalls(sessionId, agentRole, aiMessage, requests, inputTokens, outputTokens);
            }
        }

        String text = aiMessage.text();
        String answer = stripThinkingTags(text);

        return ReasoningStep.pending(text, ReasoningStep.FINISH,
                Map.of("answer", answer != null ? answer : ""),
                inputTokens, outputTokens);
    }

    /**
     * Strips {@code <think>...</think>} blocks produced by Qwen/DeepSeek-style thinking models.
     * Returns only the content after the closing tag (the actual answer).
     */
    static String stripThinkingTags(String text) {
        if (text == null) return null;
        return THINKING_TAG_PATTERN.matcher(text).replaceAll("").strip();
    }

    private ReasoningStep handleSingleToolCall(String sessionId, AiMessage aiMessage, ToolExecutionRequest req, int inputTokens, int outputTokens) {
        log.infof("LLM is attempting to call tool: '%s' with args: %s", req.name(), req.arguments());

        String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound() 
                ? WorkspaceContext.RAW_USER_GOAL.get() : null;

        if (ToolCapability.SANDBOX_EXEC.toolName().equals(req.name()) && !textPatternUtils.goalRequestsExecution(rawGoal)) {
            String code = recoverCodeForDisplay(req.arguments());
            if (code != null && !code.isBlank()) {
                log.infof("Intercepted unrequested execution call; converting to FINISH step with code display.");
                String language = textPatternUtils.inferLanguage(code);
                String markdown = String.format("```%s\n%s\n```", language != null ? language : "", code);
                return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH, 
                        Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, markdown), inputTokens, outputTokens);
            }
        }

        Map<String, Object> actionInput = deserializeParams(sessionId, req.name(), req.arguments());
        if (actionInput.containsKey("__error")) {
            return ReasoningStep.schemaError(
                    "System: Your previous response contained invalid JSON. " +
                            "Correct the format and retry. Error: " + actionInput.get("__error"),
                    inputTokens, outputTokens
            );
        }

        if (ToolCapability.MEMORY_SEARCH.toolName().equals(req.name())) {
            String query = (String) actionInput.get("query");
            if (query == null || query.isBlank() || isUnicodeCorrupted(query)) {
                String rawGoalUpdated = WorkspaceContext.RAW_USER_GOAL.isBound() 
                        ? WorkspaceContext.RAW_USER_GOAL.get() : null;
                if (rawGoalUpdated != null && !rawGoalUpdated.isBlank()) {
                    log.infof("Issue 7: Replacing garbled memory_search query '%s' with raw user goal", query);
                    actionInput.put("query", rawGoalUpdated);
                    query = rawGoalUpdated;
                }
            }

            if (query == null || query.isBlank()) {
                log.warnf("LLM emitted %s with blank query for session %s. Forcing correction.", req.name(), sessionId);
                return ReasoningStep.schemaError(
                    "System: The 'query' parameter for '" + req.name() + "' cannot be blank. " +
                    "Please provide factual keywords for the search.",
                    inputTokens, outputTokens
                );
            }
        }

        return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput, inputTokens, outputTokens);
    }

    private ReasoningStep handleMultipleToolCalls(String sessionId, String agentRole, AiMessage aiMessage, List<ToolExecutionRequest> requests, int inputTokens, int outputTokens) {
        log.infof("[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel " +
                  "tool calls: %s - routing to ParallelToolStepExecutor.",
                  sessionId, agentRole, requests.size(),
                  requests.stream()
                      .map(ToolExecutionRequest::name)
                      .collect(Collectors.joining(", ")));

        List<ParallelToolCall> calls = new ArrayList<>();
        String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound()
                ? WorkspaceContext.RAW_USER_GOAL.get() : null;

        if (isSchedulingGoal(rawGoal)) {
            ToolExecutionRequest scheduleRequest = requests.stream()
                    .filter(r -> ToolCapability.SCHEDULE_TASK.toolName().equals(r.name()))
                    .findFirst()
                    .orElse(null);
            if (scheduleRequest != null) {
                log.warnf("[PARALLEL-TOOLS] session=%s scheduling goal emitted mixed parallel tools; " +
                          "collapsing to schedule_task only.", sessionId);
                Map<String, Object> actionInput = deserializeParams(sessionId, scheduleRequest.name(),
                        scheduleRequest.arguments());
                if (actionInput.containsKey("__error")) {
                    return ReasoningStep.schemaError(
                            "System: Your previous response contained invalid JSON. " +
                                    "Correct the format and retry. Error: " + actionInput.get("__error"),
                            inputTokens, outputTokens
                    );
                }
                return ReasoningStep.pending(aiMessage.text(), scheduleRequest.name(),
                        actionInput, inputTokens, outputTokens);
            }
        }

        if (requests.stream().allMatch(r -> ToolCapability.INGEST_DOCUMENT.toolName().equals(r.name()))) {
            log.warnf("[PARALLEL-TOOLS] session=%s: LLM violated anti-parallel storage rule. Merging.", sessionId);
            String mergedText = requests.stream()
                .map(r -> deserializeParams(sessionId, r.name(), r.arguments()))
                .filter(p -> !p.containsKey("__error"))
                .map(p -> p.get("text") != null ? p.get("text") : (p.get("content") != null ? p.get("content") : p.get("value")))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.joining(", "));
            String mergedLabel = requests.stream()
                .map(r -> deserializeParams(sessionId, r.name(), r.arguments()))
                .filter(p -> !p.containsKey("__error"))
                .map(p -> p.get("label") != null ? p.get("label") : p.get("category"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst().orElse("user_facts");
            Map<String, Object> mergedParams = Map.of("label", mergedLabel, "text", mergedText);
            return ReasoningStep.pending(aiMessage.text(), ToolCapability.INGEST_DOCUMENT.toolName(), mergedParams, inputTokens, outputTokens);
        }

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            String callId = req.id() != null ? req.id() : req.name() + "-" + UUID.randomUUID();
            Map<String, Object> params = deserializeParams(sessionId, req.name(), req.arguments());
            String paramsJson = "{}";
            try {
                paramsJson = objectMapper.writeValueAsString(params);
            } catch (Exception e) {
                log.warnf("Failed to serialize params for tool %s: %s", req.name(), LogUtil.rootMessage(e));
            }
            calls.add(ParallelToolCall.pending(callId, req.name(), params, paramsJson));
        }

        Map<String, Object> batchPayload;
        try {
            String batchJson = objectMapper.writeValueAsString(calls);
            batchPayload = Map.of("batch", batchJson);
        } catch (Exception e) {
            log.errorf("Failed to serialize parallel batch for session %s: %s", sessionId, LogUtil.rootMessage(e));
            ToolExecutionRequest req = requests.getFirst();
            Map<String, Object> actionInput = deserializeParams(sessionId, req.name(), req.arguments());
            return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput, inputTokens, outputTokens);
        }

        return ReasoningStep.parallelBatch(aiMessage.text(), batchPayload, inputTokens, outputTokens);
    }

    private Map<String, Object> deserializeParams(String sessionId, String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        
        // Phase 1: Try direct parse
        try {
            return doDeserialize(argsJson);
        } catch (Exception e) {
            log.warnf("Initial JSON parse failed for session %s: %s. Attempting repair.", sessionId, LogUtil.rootMessage(e));
            
            // Phase 2: Repair and retry
            try {
                String repaired = sanitizeJson(argsJson);
                return doDeserialize(repaired);
            } catch (Exception e2) {
                if (ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) {
                    Map<String, Object> recovered = recoverSandboxExecParams(argsJson);
                    if (!recovered.isEmpty()) {
                        log.warnf("Recovered malformed sandbox_exec parameters for session %s", sessionId);
                        return recovered;
                    }
                }
                log.errorf("JSON repair failed for session %s: %s", sessionId, e2.getMessage());
                return Map.of("__error", e.getMessage(), "parameters", argsJson);
            }
        }
    }

    private Map<String, Object> doDeserialize(String json) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        if (parsed != null) {
            coerceBooleans(parsed);
        }
        return parsed != null ? parsed : Map.of();
    }

    @SuppressWarnings("unchecked")
    private void coerceBooleans(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s) {
                if ("true".equalsIgnoreCase(s)) {
                    entry.setValue(Boolean.TRUE);
                } else if ("false".equalsIgnoreCase(s)) {
                    entry.setValue(Boolean.FALSE);
                }
            } else if (val instanceof Map) {
                coerceBooleans((Map<String, Object>) val);
            } else if (val instanceof List list) {
                coerceBooleans(list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void coerceBooleans(List<Object> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String s) {
                if ("true".equalsIgnoreCase(s)) {
                    list.set(i, Boolean.TRUE);
                } else if ("false".equalsIgnoreCase(s)) {
                    list.set(i, Boolean.FALSE);
                }
            } else if (item instanceof Map) {
                coerceBooleans((Map<String, Object>) item);
            } else if (item instanceof List innerList) {
                coerceBooleans(innerList);
            }
        }
    }

    /**
     * Attempts to repair common LLM tool-calling JSON errors:
     * 1. Unescaped newlines within string values
     * 2. Unescaped double-quotes within what looks like a code field
     */
    private String sanitizeJson(String json) {
        if (json == null) return null;
        
        // Replace literal newlines with \n ONLY if they are not escaped
        // This is a naive heuristic but works for code blocks.
        // We look for a newline that is preceded by something other than \
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        char prev = 0;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            
            if (c == '"' && prev != '\\') {
                inString = !inString;
            }
            
            if (inString && (c == '\n' || c == '\r')) {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
            prev = c;
        }
        
        return sb.toString();
    }

    private Map<String, Object> recoverSandboxExecParams(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();

        int codeKey = argsJson.indexOf("\"code\"");
        if (codeKey < 0) return Map.of();

        int codeStart = argsJson.indexOf('"', argsJson.indexOf(':', codeKey) + 1);
        if (codeStart < 0) return Map.of();

        String rawCode;
        String language = null;

        int languageKey = argsJson.lastIndexOf("\"language\"");
        if (languageKey >= 0 && languageKey > codeStart) {
            int codeEnd = argsJson.lastIndexOf("\",", languageKey);
            if (codeEnd >= 0 && codeEnd > codeStart) {
                rawCode = argsJson.substring(codeStart + 1, codeEnd);
                Matcher matcher = LANGUAGE_FIELD_PATTERN.matcher(argsJson);
                if (matcher.find()) {
                    language = matcher.group(1);
                }
            } else {
                // language field exists but code field is truncated or messed up
                rawCode = argsJson.substring(codeStart + 1);
            }
        } else {
            // No language field - assume truncated
            rawCode = argsJson.substring(codeStart + 1);
        }

        String code = rawCode
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        
        // Clean up trailing garbage from truncation (quotes and commas only)
        // DO NOT strip '}' as it might be a valid block end.
        if (code.endsWith("\"") || code.endsWith(",")) {
             code = code.substring(0, code.length() - 1);
        }

        if (language == null || language.isBlank()) {
            language = textPatternUtils.inferLanguage(code);
        }

        if (code.isBlank() || language == null || language.isBlank()) return Map.of();
        return Map.of("code", code, "language", language);
    }

    private String recoverCodeForDisplay(String argsJson) {
        Map<String, Object> recovered = recoverSandboxExecParams(argsJson);
        return (String) recovered.get("code");
    }

    private boolean isSchedulingGoal(String rawGoal) {
        if (rawGoal == null || rawGoal.isBlank()) return false;
        String lower = rawGoal.toLowerCase();
        boolean hasScheduleVerb = lower.contains("schedule")
                || lower.contains("remind")
                || lower.contains("alarm")
                || lower.contains("notify me")
                || lower.contains("set up");
        return hasScheduleVerb
                || RELATIVE_SCHEDULE_PATTERN.matcher(lower).find()
                || ABSOLUTE_SCHEDULE_PATTERN.matcher(lower).find();
    }

    private boolean isUnicodeCorrupted(String text) {
        if (text == null || text.isBlank()) return false;
        // Count Unicode replacement characters or private-use area characters (Issue 7)
        long badChars = text.chars()
                .filter(c -> c == 0xFFFD || (c >= 0xE000 && c <= 0xF8FF))
                .count();
        return badChars > 0;
    }
}
