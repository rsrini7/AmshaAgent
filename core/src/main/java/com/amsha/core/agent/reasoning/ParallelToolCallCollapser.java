package com.amsha.core.agent.reasoning;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Collapses multiple parallel tool execution requests into a single {@link ReasoningStep}.
 * Extracted from {@link ReasoningStepParser} to keep that class under PMD size limits and to
 * isolate the cognitive complexity of multi-tool-call normalization.
 */
@ApplicationScoped
public class ParallelToolCallCollapser {

    private static final Logger log = Logger.getLogger(ParallelToolCallCollapser.class);

    private static final Pattern RELATIVE_SCHEDULE_PATTERN = Pattern.compile(
            "\\b(in\\s+\\d+\\s*(min|mins|minute|minutes|hour|hours)|in\\s+one\\s+(min|minute|hour))\\b");
    private static final Pattern ABSOLUTE_SCHEDULE_PATTERN = Pattern.compile(
            "\\b(at\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)?|every\\s+(day|hour|minute|week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b");
    private static final Pattern WRITE_MEMORY_GOAL_PATTERN = Pattern.compile(
            "\\b(save|store|remember|ingest|add to memory|note that|call me|my name is|refer to me as|address me as)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final ToolCallParameterParser parameterParser;

    @Inject
    public ParallelToolCallCollapser(ObjectMapper objectMapper, ToolCallParameterParser parameterParser) {
        this.objectMapper = objectMapper;
        this.parameterParser = parameterParser;
    }

    /**
     * Collapses a batch of tool requests into a single step.
     */
    ReasoningStep collapse(
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
                requests.stream()
                        .map(r -> ReasoningStepParserUtils.sanitizeToolName(r.name()))
                        .collect(Collectors.joining(", ")));

        StepDecision decision = tryCollapseToSchedule(sessionId, aiMessage, requests, inputTokens, outputTokens);
        if (decision != null) return decision.step();

        decision = tryMergeIngestDocuments(sessionId, aiMessage, requests, inputTokens, outputTokens);
        if (decision != null) return decision.step();

        decision = tryDropStorageFromMixedBatch(sessionId, agentRole, aiMessage, requests, inputTokens, outputTokens);
        if (decision != null) return decision.step();

        decision = tryCollapseSelfEvolve(sessionId, aiMessage, requests, inputTokens, outputTokens);
        if (decision != null) return decision.step();

        return buildParallelBatch(sessionId, aiMessage, requests, inputTokens, outputTokens);
    }

    private StepDecision tryCollapseToSchedule(
            String sessionId,
            AiMessage aiMessage,
            List<ToolExecutionRequest> requests,
            int inputTokens,
            int outputTokens) {
        String rawGoal = rawUserGoal();
        if (!isSchedulingGoal(rawGoal)) return null;

        ToolExecutionRequest scheduleRequest = requests.stream()
                .filter(r -> ToolCapability.SCHEDULE_TASK.toolName().equals(r.name()))
                .findFirst()
                .orElse(null);
        if (scheduleRequest == null) return null;

        log.warnf(
                "[PARALLEL-TOOLS] session=%s scheduling goal emitted mixed parallel tools; "
                        + "collapsing to schedule_task only.",
                sessionId);
        Map<String, Object> actionInput =
                deserializeParams(sessionId, scheduleRequest.name(), scheduleRequest.arguments());
        if (actionInput.containsKey("__error")) {
            return new StepDecision(ReasoningStep.schemaError(
                    "System: Your previous response contained invalid JSON. " + "Correct the format and retry. Error: "
                            + actionInput.get("__error"),
                    inputTokens,
                    outputTokens));
        }
        return new StepDecision(ReasoningStep.pending(
                aiMessage.text(), scheduleRequest.name(), actionInput, inputTokens, outputTokens));
    }

    private StepDecision tryMergeIngestDocuments(
            String sessionId,
            AiMessage aiMessage,
            List<ToolExecutionRequest> requests,
            int inputTokens,
            int outputTokens) {
        if (!requests.stream()
                .allMatch(r -> ToolCapability.INGEST_DOCUMENT.toolName().equals(r.name()))) {
            return null;
        }

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
        return new StepDecision(ReasoningStep.pending(
                aiMessage.text(), ToolCapability.INGEST_DOCUMENT.toolName(), mergedParams, inputTokens, outputTokens));
    }

    private StepDecision tryDropStorageFromMixedBatch(
            String sessionId,
            String agentRole,
            AiMessage aiMessage,
            List<ToolExecutionRequest> requests,
            int inputTokens,
            int outputTokens) {
        String rawGoal = rawUserGoal();
        if (isWriteMemoryGoal(rawGoal)
                || requests.stream()
                        .noneMatch(
                                r -> ToolCapability.INGEST_DOCUMENT.toolName().equals(r.name()))) {
            return null;
        }

        List<ToolExecutionRequest> nonStorageRequests = requests.stream()
                .filter(r -> !ToolCapability.INGEST_DOCUMENT.toolName().equals(r.name()))
                .toList();
        if (nonStorageRequests.isEmpty()) return null;

        log.warnf(
                "[PARALLEL-TOOLS] session=%s: dropping stale ingest_document from mixed non-storage batch.", sessionId);
        if (nonStorageRequests.size() == 1) {
            ToolExecutionRequest selected = nonStorageRequests.getFirst();
            Map<String, Object> actionInput = deserializeParams(sessionId, selected.name(), selected.arguments());
            if (actionInput.containsKey("__error")) {
                return new StepDecision(ReasoningStep.schemaError(
                        "System: Your previous response contained invalid JSON. "
                                + "Correct the format and retry. Error: " + actionInput.get("__error"),
                        inputTokens,
                        outputTokens));
            }
            return new StepDecision(ReasoningStep.pending(
                    aiMessage.text(),
                    ReasoningStepParserUtils.sanitizeToolName(selected.name()),
                    actionInput,
                    inputTokens,
                    outputTokens));
        }
        return new StepDecision(
                collapse(sessionId, agentRole, aiMessage, nonStorageRequests, inputTokens, outputTokens));
    }

    private StepDecision tryCollapseSelfEvolve(
            String sessionId,
            AiMessage aiMessage,
            List<ToolExecutionRequest> requests,
            int inputTokens,
            int outputTokens) {
        if (!requests.stream()
                .allMatch(r -> ToolCapability.SELF_EVOLVE_REFLECT.toolName().equals(r.name()))) {
            return null;
        }

        log.warnf(
                "[PARALLEL-TOOLS] session=%s: LLM emitted multiple self_evolve_reflect calls; collapsing to one action.",
                sessionId);
        ToolExecutionRequest selected = requests.stream()
                .filter(r -> isSelfEvolveStatusAction(sessionId, r))
                .findFirst()
                .orElse(requests.getFirst());
        Map<String, Object> actionInput = deserializeParams(sessionId, selected.name(), selected.arguments());
        if (actionInput.containsKey("__error")) {
            return new StepDecision(ReasoningStep.schemaError(
                    "System: Your previous response contained invalid JSON. " + "Correct the format and retry. Error: "
                            + actionInput.get("__error"),
                    inputTokens,
                    outputTokens));
        }
        return new StepDecision(ReasoningStep.pending(
                aiMessage.text(),
                ReasoningStepParserUtils.sanitizeToolName(selected.name()),
                actionInput,
                inputTokens,
                outputTokens));
    }

    private ReasoningStep buildParallelBatch(
            String sessionId,
            AiMessage aiMessage,
            List<ToolExecutionRequest> requests,
            int inputTokens,
            int outputTokens) {
        List<ParallelToolCall> calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            String name = ReasoningStepParserUtils.sanitizeToolName(req.name());
            String callId = req.id() != null ? req.id() : name + "-" + UUID.randomUUID();
            Map<String, Object> params = deserializeParams(sessionId, name, req.arguments());
            // Collapse the LLM's habitual double-emit of a web tool: two parallel calls of the
            // same tool with the same target identity (e.g. an identical search query or URL) are
            // redundant and become one call, while distinct targets are preserved. Tools without a
            // known identity key fall back to full-parameter equality (prior behaviour).
            String target = identityKeyOf(name, params);
            String signature = name + "\u0000" + target;
            if (!seen.add(signature)) {
                log.warnf(
                        "[PARALLEL-TOOLS] session=%s: dropping duplicate parallel tool call '%s' (target=%s)",
                        sessionId, name, target);
                continue;
            }
            String paramsJson = "{}";
            try {
                paramsJson = objectMapper.writeValueAsString(params);
            } catch (Exception e) {
                log.warnf("Failed to serialize params for tool %s: %s", name, LogUtil.rootMessage(e));
            }
            calls.add(ParallelToolCall.pending(callId, name, params, paramsJson));
        }

        if (calls.size() == 1) {
            ParallelToolCall single = calls.getFirst();
            return ReasoningStep.pending(
                    aiMessage.text(), single.toolName(), single.params(), inputTokens, outputTokens);
        }

        Map<String, Object> batchPayload;
        try {
            String batchJson = objectMapper.writeValueAsString(calls);
            batchPayload = Map.of("batch", batchJson);
        } catch (Exception e) {
            log.errorf("Failed to serialize parallel batch for session %s: %s", sessionId, LogUtil.rootMessage(e));
            ToolExecutionRequest req = requests.getFirst();
            String name = ReasoningStepParserUtils.sanitizeToolName(req.name());
            Map<String, Object> actionInput = deserializeParams(sessionId, name, req.arguments());
            return ReasoningStep.pending(aiMessage.text(), name, actionInput, inputTokens, outputTokens);
        }

        return ReasoningStep.parallelBatch(aiMessage.text(), batchPayload, inputTokens, outputTokens);
    }

    /**
     * The parameter that uniquely identifies the work a tool performs. Two parallel calls of the
     * same tool sharing this value are redundant (LLM double-emit); calls with distinct values are
     * distinct work. Unknown tools fall back to full-parameter equality.
     */
    private static final Map<String, List<String>> IDENTITY_KEYS = Map.of(
            "serper_web_search", List.of("query", "q"),
            "brave_web_search", List.of("query", "q"),
            "web_fetch", List.of("url", "link", "uri"),
            "browser_navigate", List.of("url", "targetUrl", "uri", "link"));

    private String identityKeyOf(String toolName, Map<String, Object> params) {
        for (String candidate : IDENTITY_KEYS.getOrDefault(toolName, List.of())) {
            Object value = params.get(candidate);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        // Fallback: full-parameter equality (preserves prior behaviour for all other tools).
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return String.valueOf(params.hashCode());
        }
    }

    private boolean isSelfEvolveStatusAction(String sessionId, ToolExecutionRequest request) {
        Map<String, Object> params = deserializeParams(sessionId, request.name(), request.arguments());
        Object action = params.get("action");
        return action != null && "status".equalsIgnoreCase(action.toString());
    }

    private boolean isWriteMemoryGoal(String rawGoal) {
        return rawGoal != null && WRITE_MEMORY_GOAL_PATTERN.matcher(rawGoal).find();
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

    private String rawUserGoal() {
        return WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null;
    }

    private Map<String, Object> deserializeParams(String sessionId, String toolName, String argsJson) {
        return parameterParser.deserializeParams(sessionId, toolName, argsJson);
    }

    private record StepDecision(ReasoningStep step) {}
}
