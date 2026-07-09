package com.amsha.core.agent.reasoning;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Parses the ChatResponse from LangChain4j into a ReasoningStep.
 */
@ApplicationScoped
@SuppressWarnings("java:S1192")
public class ReasoningStepParser {

    private static final Logger log = Logger.getLogger(ReasoningStepParser.class);

    /** Strips <think>...</think> blocks from Qwen/DeepSeek-style thinking models. */
    private static final Pattern THINKING_TAG_PATTERN = Pattern.compile("(?s)<think>.*?</think>\\s*", Pattern.DOTALL);

    /** Max answer length before we suspect chain-of-thought thinking leaked into the answer. */
    private static final int MAX_ANSWER_LENGTH = 4000;

    /** Marks the boundary between chain-of-thought reasoning and the actual answer. */
    private static final Pattern THINKING_ANSWER_BOUNDARY = Pattern.compile(
            "(?m)^\\s*(?:Response|Final (?:Answer|Decision|Response)|Output)\\s*[:.]\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern LANGUAGE_FIELD_PATTERN = Pattern.compile("\"language\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RELATIVE_SCHEDULE_PATTERN = Pattern.compile(
            "\\b(in\\s+\\d+\\s*(min|mins|minute|minutes|hour|hours)|in\\s+one\\s+(min|minute|hour))\\b");
    private static final Pattern ABSOLUTE_SCHEDULE_PATTERN = Pattern.compile(
            "\\b(at\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?|every\\s+(day|hour|minute|week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b");

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

        return ReasoningStep.pending(
                text, ReasoningStep.FINISH, Map.of("answer", answer != null ? answer : ""), inputTokens, outputTokens);
    }

    /**
     * Strips thinking/reasoning from LLM responses.
     * Handles:
     * 1. Explicit {@code <think>...</think>} tags (Qwen/DeepSeek structured thinking)
     * 2. Untagged chain-of-thought: when the answer exceeds {@value MAX_ANSWER_LENGTH} chars,
     *    looks for a "Final Response:" / "Response:" boundary, or takes the last portion.
     */
    static String stripThinkingTags(String text) {
        if (text == null) return null;

        // 1. Strip explicit <think>...</think> tags
        String stripped = THINKING_TAG_PATTERN.matcher(text).replaceAll("").strip();
        if (!stripped.isEmpty() && stripped.length() <= MAX_ANSWER_LENGTH) {
            return stripped;
        }

        // 2. If still too long, check for a thinking/answer boundary marker
        if (text.length() > MAX_ANSWER_LENGTH) {
            Matcher boundary = THINKING_ANSWER_BOUNDARY.matcher(text);
            int lastBoundary = -1;
            while (boundary.find()) {
                lastBoundary = boundary.end();
            }
            if (lastBoundary > 0 && lastBoundary < text.length() - 1) {
                String afterBoundary = text.substring(lastBoundary).strip();
                if (!afterBoundary.isEmpty() && afterBoundary.length() <= MAX_ANSWER_LENGTH) {
                    return afterBoundary;
                }
            }

            // 3. No boundary found — take the last chunk as the answer
            // (LLM thinking usually comes first, answer at the end)
            return text.substring(text.length() - MAX_ANSWER_LENGTH).strip();
        }

        return stripped;
    }

    private static String sanitizeToolName(String name) {
        return ReasoningStepParserUtils.sanitizeToolName(name);
    }

    private ReasoningStep handleSingleToolCall(
            String sessionId, AiMessage aiMessage, ToolExecutionRequest req, int inputTokens, int outputTokens) {
        String rawName = req.name();
        String toolName = sanitizeToolName(rawName);
        if (rawName != null && !rawName.equals(toolName)) {
            log.infof("Sanitized tool name: '%s' -> '%s'", rawName, toolName);
        }
        log.infof("LLM is attempting to call tool: '%s' with args: %s", toolName, req.arguments());

        ReasoningStep intercepted = interceptUnrequestedExecution(toolName, req, aiMessage, inputTokens, outputTokens);
        if (intercepted != null) return intercepted;

        Map<String, Object> actionInput = deserializeParams(sessionId, toolName, req.arguments());
        if (actionInput.containsKey("__error")) {
            return ReasoningStep.schemaError(
                    "System: Your previous response contained invalid JSON. " + "Correct the format and retry. Error: "
                            + actionInput.get("__error"),
                    inputTokens,
                    outputTokens);
        }

        ReasoningStep fixed = fixupMemorySearchQuery(toolName, actionInput, sessionId, inputTokens, outputTokens);
        if (fixed != null) return fixed;

        return ReasoningStep.pending(aiMessage.text(), toolName, actionInput, inputTokens, outputTokens);
    }

    private ReasoningStep interceptUnrequestedExecution(
            String toolName, ToolExecutionRequest req, AiMessage aiMessage, int inputTokens, int outputTokens) {
        if (!ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) return null;

        // Skill roles (e.g. skill:remote-browser) are designed to execute commands —
        // skip the "unrequested execution" guard; execution IS their purpose.
        String agentRole = WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null;
        if (agentRole != null && AgentRoleEnum.isSkillRole(agentRole)) return null;

        String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null;
        if (textPatternUtils.goalRequestsExecution(rawGoal)) return null;

        String code = recoverCodeForDisplay(req.arguments());
        if (code == null || code.isBlank()) return null;

        log.infof("Intercepted unrequested execution call; converting to FINISH step with code display.");
        String language = textPatternUtils.inferLanguage(code);
        String markdown = String.format("```%s\n%s\n```", language != null ? language : "", code);
        return ReasoningStep.pending(
                aiMessage.text(),
                ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, markdown),
                inputTokens,
                outputTokens);
    }

    private ReasoningStep fixupMemorySearchQuery(
            String toolName, Map<String, Object> actionInput, String sessionId, int inputTokens, int outputTokens) {
        if (!ToolCapability.RECALL_MEMORY_SEARCH.toolName().equals(toolName)
                && !ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName().equals(toolName)) {
            return null;
        }

        String query = (String) actionInput.get("query");
        if (query == null || query.isBlank() || isUnicodeCorrupted(query)) {
            String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null;
            if (rawGoal != null && !rawGoal.isBlank()) {
                log.infof("Issue 7: Replacing garbled memory search query '%s' with raw user goal", query);
                actionInput.put("query", rawGoal);
                query = rawGoal;
            }
        }

        if (query == null || query.isBlank()) {
            log.warnf("LLM emitted %s with blank query for session %s. Forcing correction.", toolName, sessionId);
            return ReasoningStep.schemaError(
                    "System: The 'query' parameter for '" + toolName + "' cannot be blank. "
                            + "Please provide factual keywords for the search.",
                    inputTokens,
                    outputTokens);
        }
        return null;
    }

    private ReasoningStep handleMultipleToolCalls(
            String sessionId,
            String agentRole,
            AiMessage aiMessage,
            List<ToolExecutionRequest> requests,
            int inputTokens,
            int outputTokens) {
        log.infof(
                "[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel "
                        + "tool calls: %s - routing to ParallelToolStepExecutor.",
                sessionId,
                agentRole,
                requests.size(),
                requests.stream().map(r -> sanitizeToolName(r.name())).collect(Collectors.joining(", ")));

        List<ParallelToolCall> calls = new ArrayList<>();
        String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null;

        if (isSchedulingGoal(rawGoal)) {
            ToolExecutionRequest scheduleRequest = requests.stream()
                    .filter(r -> ToolCapability.SCHEDULE_TASK.toolName().equals(r.name()))
                    .findFirst()
                    .orElse(null);
            if (scheduleRequest != null) {
                log.warnf(
                        "[PARALLEL-TOOLS] session=%s scheduling goal emitted mixed parallel tools; "
                                + "collapsing to schedule_task only.",
                        sessionId);
                Map<String, Object> actionInput =
                        deserializeParams(sessionId, scheduleRequest.name(), scheduleRequest.arguments());
                if (actionInput.containsKey("__error")) {
                    return ReasoningStep.schemaError(
                            "System: Your previous response contained invalid JSON. "
                                    + "Correct the format and retry. Error: " + actionInput.get("__error"),
                            inputTokens,
                            outputTokens);
                }
                return ReasoningStep.pending(
                        aiMessage.text(), scheduleRequest.name(), actionInput, inputTokens, outputTokens);
            }
        }

        if (requests.stream()
                .allMatch(r -> ToolCapability.INGEST_DOCUMENT.toolName().equals(r.name()))) {
            log.warnf("[PARALLEL-TOOLS] session=%s: LLM violated anti-parallel storage rule. Merging.", sessionId);
            String mergedText = requests.stream()
                    .map(r -> deserializeParams(sessionId, r.name(), r.arguments()))
                    .filter(p -> !p.containsKey("__error"))
                    .map(p -> p.get("text") != null
                            ? p.get("text")
                            : (p.get("content") != null
                                    ? p.get("content")
                                    : (p.get("value") != null
                                            ? p.get("value")
                                            : (p.get("document") != null
                                                    ? p.get("document")
                                                    : (p.get("doc") != null ? p.get("doc") : p.get("data"))))))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            String mergedLabel = requests.stream()
                    .map(r -> deserializeParams(sessionId, r.name(), r.arguments()))
                    .filter(p -> !p.containsKey("__error"))
                    .map(p -> p.get("label") != null ? p.get("label") : p.get("category"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .findFirst()
                    .orElse("user_facts");
            Map<String, Object> mergedParams = Map.of("label", mergedLabel, "text", mergedText);
            return ReasoningStep.pending(
                    aiMessage.text(),
                    ToolCapability.INGEST_DOCUMENT.toolName(),
                    mergedParams,
                    inputTokens,
                    outputTokens);
        }

        if (requests.stream()
                .allMatch(r -> ToolCapability.SELF_EVOLVE_REFLECT.toolName().equals(r.name()))) {
            log.warnf(
                    "[PARALLEL-TOOLS] session=%s: LLM emitted multiple self_evolve_reflect calls; collapsing to one action.",
                    sessionId);
            ToolExecutionRequest selected = requests.stream()
                    .filter(r -> isSelfEvolveStatusAction(sessionId, r))
                    .findFirst()
                    .orElse(requests.getFirst());
            Map<String, Object> actionInput = deserializeParams(sessionId, selected.name(), selected.arguments());
            if (actionInput.containsKey("__error")) {
                return ReasoningStep.schemaError(
                        "System: Your previous response contained invalid JSON. "
                                + "Correct the format and retry. Error: " + actionInput.get("__error"),
                        inputTokens,
                        outputTokens);
            }
            return ReasoningStep.pending(
                    aiMessage.text(), sanitizeToolName(selected.name()), actionInput, inputTokens, outputTokens);
        }

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            String name = sanitizeToolName(req.name());
            String callId = req.id() != null ? req.id() : name + "-" + UUID.randomUUID();
            Map<String, Object> params = deserializeParams(sessionId, name, req.arguments());
            String paramsJson = "{}";
            try {
                paramsJson = objectMapper.writeValueAsString(params);
            } catch (Exception e) {
                log.warnf("Failed to serialize params for tool %s: %s", name, LogUtil.rootMessage(e));
            }
            calls.add(ParallelToolCall.pending(callId, name, params, paramsJson));
        }

        Map<String, Object> batchPayload;
        try {
            String batchJson = objectMapper.writeValueAsString(calls);
            batchPayload = Map.of("batch", batchJson);
        } catch (Exception e) {
            log.errorf("Failed to serialize parallel batch for session %s: %s", sessionId, LogUtil.rootMessage(e));
            ToolExecutionRequest req = requests.getFirst();
            String name = sanitizeToolName(req.name());
            Map<String, Object> actionInput = deserializeParams(sessionId, name, req.arguments());
            return ReasoningStep.pending(aiMessage.text(), name, actionInput, inputTokens, outputTokens);
        }

        return ReasoningStep.parallelBatch(aiMessage.text(), batchPayload, inputTokens, outputTokens);
    }

    private boolean isSelfEvolveStatusAction(String sessionId, ToolExecutionRequest request) {
        Map<String, Object> params = deserializeParams(sessionId, request.name(), request.arguments());
        Object action = params.get("action");
        return action != null && "status".equalsIgnoreCase(action.toString());
    }

    private Map<String, Object> deserializeParams(String sessionId, String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();

        // Phase 1: Try direct parse
        try {
            return doDeserialize(argsJson);
        } catch (Exception e) {
            log.warnf(
                    "Initial JSON parse failed for session %s: %s. Attempting repair.",
                    sessionId, LogUtil.rootMessage(e));

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
                log.errorf("JSON repair failed for session %s: %s", sessionId, LogUtil.rootMessage(e2));
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

    private void coerceBooleans(Map<String, Object> map) {
        ReasoningStepParserUtils.coerceBooleans(map);
    }

    private String sanitizeJson(String json) {
        return ReasoningStepParserUtils.sanitizeJson(json);
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

        String code = rawCode.replace("\\r", "\r")
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
        String lower = rawGoal.toLowerCase(Locale.ROOT);
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
