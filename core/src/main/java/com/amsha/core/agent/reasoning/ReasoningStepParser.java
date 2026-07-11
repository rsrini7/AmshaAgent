package com.amsha.core.agent.reasoning;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.TextPatternUtils;

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

    private final TextPatternUtils textPatternUtils;
    private final ToolCallParameterParser parameterParser;
    private final ParallelToolCallCollapser parallelCollapser;

    protected ReasoningStepParser() {
        this.textPatternUtils = null;
        this.parameterParser = null;
        this.parallelCollapser = null;
    }

    @Inject
    public ReasoningStepParser(
            TextPatternUtils textPatternUtils,
            ToolCallParameterParser parameterParser,
            ParallelToolCallCollapser parallelCollapser) {
        this.textPatternUtils = textPatternUtils;
        this.parameterParser = parameterParser;
        this.parallelCollapser = parallelCollapser;
    }

    public ReasoningStep parse(String sessionId, String agentRole, ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (requests.size() == 1) {
                return handleSingleToolCall(
                        sessionId, agentRole, aiMessage, requests.getFirst(), inputTokens, outputTokens);
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
            String sessionId,
            String agentRole,
            AiMessage aiMessage,
            ToolExecutionRequest req,
            int inputTokens,
            int outputTokens) {
        String rawName = req.name();
        String toolName = sanitizeToolName(rawName);
        if (rawName != null && !rawName.equals(toolName)) {
            log.infof("Sanitized tool name: '%s' -> '%s'", rawName, toolName);
        }
        log.infof("LLM is attempting to call tool: '%s' with args: %s", toolName, req.arguments());

        ReasoningStep intercepted =
                interceptUnrequestedExecution(toolName, agentRole, req, aiMessage, inputTokens, outputTokens);
        if (intercepted != null) return intercepted;

        Map<String, Object> actionInput = parameterParser.deserializeParams(sessionId, toolName, req.arguments());
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
            String toolName,
            String agentRole,
            ToolExecutionRequest req,
            AiMessage aiMessage,
            int inputTokens,
            int outputTokens) {
        if (!ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) return null;

        // Skill roles (e.g. skill:remote-browser) are designed to execute commands —
        // skip the "unrequested execution" guard; execution IS their purpose.
        String effectiveRole = agentRole != null
                ? agentRole
                : (WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null);
        if (effectiveRole != null && AgentRoleEnum.isSkillRole(effectiveRole)) return null;

        String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null;
        if (textPatternUtils.goalRequestsExecution(rawGoal)) return null;

        String code = parameterParser.recoverCodeForDisplay(req.arguments());
        if (code == null || code.isBlank()) return null;

        log.infof("Intercepted unrequested execution call; converting to FINISH step with code display.");
        String language = textPatternUtils.inferLanguage(code);
        String markdown = String.format("```%s\n%s\n```", language != null ? language : "", code);
        return ReasoningStep.pending(
                aiMessage.text(),
                ReasoningStep.FINISH,
                Map.of(
                        ReasoningStep.ACTION_INPUT_ANSWER_KEY,
                        markdown,
                        ReasoningStep.ACTION_INPUT_INTERCEPTED_EXECUTION_KEY,
                        true),
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
        return parallelCollapser.collapse(sessionId, agentRole, aiMessage, requests, inputTokens, outputTokens);
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
