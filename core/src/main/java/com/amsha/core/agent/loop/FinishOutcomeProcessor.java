package com.amsha.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.routing.InfiniteLoopChatModel;
import com.amsha.core.tools.CodeLanguageDetector;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;

import org.apache.commons.text.StringEscapeUtils;
import org.jboss.logging.Logger;

public class FinishOutcomeProcessor {

    private static final Logger log = Logger.getLogger(FinishOutcomeProcessor.class);
    static final int SALVAGE_MIN_LENGTH = 5;
    static final String SALVAGE_REJECT_PHRASE = "completed the task";

    private static final Pattern INTERNAL_JVM_ERROR_PATTERN = Pattern.compile(
            "(?:java\\.lang\\.)?(?:NullPointerException|IllegalArgumentException|"
                    + "IllegalStateException|StackOverflowError|OutOfMemoryError)(?::\\s.+)",
            Pattern.MULTILINE);

    private static final Pattern TOOL_CALL_JSON_LEAK =
            Pattern.compile("(?s)\\{\\s*\"(tool|action|function|callId|content|metadata)\"\\s*:.*?\\}", Pattern.DOTALL);

    private static final Pattern PREFIX_TOKEN_LEAK = Pattern.compile(
            "(?i)^(\\s*[*_]*(FINISH:?|Action:\\s*FINISH|Answer:|Final Answer:|Response:|<\\s*finn?ish\\s*/?\\s*>)[*_]*\\s*)+",
            Pattern.MULTILINE);

    private static final Pattern SUFFIX_TOKEN_LEAK = Pattern.compile(
            "(?i)\\s*[*_]*(FINISH:?|Action:\\s*FINISH|<\\s*finn?ish\\s*/?\\s*>)[*_]*\\s*$", Pattern.MULTILINE);

    private static final Pattern FENCE_ONLY_PATTERN =
            Pattern.compile("^\\s*```[a-z]*\\s*[}\\]]*\\s*```\\s*$", Pattern.CASE_INSENSITIVE);

    private static final List<String> SEARCH_TOOL_NAMES = List.of(
            ToolCapability.WEB_SEARCH_SERPER.toolName(),
            ToolCapability.WEB_SEARCH_BRAVE.toolName(),
            "web_search",
            "search");

    private final AnswerGuardStrategy qualityGuard;
    private final CodeGenerationPolicy codePolicy;
    private final RateLimitTracker rateLimitTracker;
    private final CorrectionStrategy correctionStrategy;
    private final TextPatternUtils textPatternUtils;
    private final ToolRegistry toolRegistry;
    private final HallucinationDetector hallucinationDetector;
    private final IntentClassificationService intentClassifier;

    public FinishOutcomeProcessor(
            AnswerGuardStrategy qualityGuard,
            CodeGenerationPolicy codePolicy,
            RateLimitTracker rateLimitTracker,
            CorrectionStrategy correctionStrategy,
            TextPatternUtils textPatternUtils,
            ToolRegistry toolRegistry,
            HallucinationDetector hallucinationDetector,
            IntentClassificationService intentClassifier) {
        this.qualityGuard = qualityGuard;
        this.codePolicy = codePolicy;
        this.rateLimitTracker = rateLimitTracker;
        this.correctionStrategy = correctionStrategy;
        this.textPatternUtils = textPatternUtils;
        this.toolRegistry = toolRegistry;
        this.hallucinationDetector = hallucinationDetector;
        this.intentClassifier = intentClassifier;
    }

    public FinishOutcomeProcessor(
            AnswerGuardStrategy qualityGuard,
            CodeGenerationPolicy codePolicy,
            RateLimitTracker rateLimitTracker,
            CorrectionStrategy correctionStrategy,
            TextPatternUtils textPatternUtils,
            ToolRegistry toolRegistry,
            HallucinationDetector hallucinationDetector) {
        this(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                correctionStrategy,
                textPatternUtils,
                toolRegistry,
                hallucinationDetector,
                new IntentClassificationService());
    }

    private String findSearchTool(String availableTools) {
        return SEARCH_TOOL_NAMES.stream()
                .filter(availableTools::contains)
                .findFirst()
                .orElse(null);
    }

    public String extractCandidateAnswer(ReasoningStep step) {
        String candidateAnswer = step.thought();
        if (step.actionInput() != null && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
            candidateAnswer = String.valueOf(step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY));
        }
        if (candidateAnswer != null) {
            candidateAnswer = candidateAnswer.strip();
            candidateAnswer = PREFIX_TOKEN_LEAK.matcher(candidateAnswer).replaceFirst("");
            candidateAnswer = SUFFIX_TOKEN_LEAK.matcher(candidateAnswer).replaceFirst("");
            candidateAnswer = candidateAnswer.strip();
        }
        return candidateAnswer;
    }

    public String unescapeAndNormalize(String candidateAnswer) {
        if (candidateAnswer == null) return null;
        String answer = StringEscapeUtils.unescapeHtml4(candidateAnswer);
        answer = answer.replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0026", "&")
                .replace("\\u0027", "'");
        return sanitizeAnswer(answer);
    }

    public FinishActionHandler.FinishOutcome checkHallucination(
            String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        return hallucinationDetector.checkHallucination(sessionId, history, step, answer);
    }

    public FinishActionHandler.FinishOutcome applyCodePolicy(
            String sessionId,
            String agentRole,
            String goal,
            List<ReasoningStep> history,
            String answer,
            ReasoningStep step) {
        if (answer != null
                && codePolicy.isGeneratedCodeBlock(answer, agentRole)
                && !codePolicy.canGenerateCode(agentRole)) {
            log.infof("FINISH-HANDLER code-gen blocked for role '%s' in session %s", agentRole, sessionId);
            ReasoningStep cleanStep = step.withObservation("Code generation blocked for " + agentRole);

            boolean inDeepDelegation = sessionId != null
                    && sessionId.split(Pattern.quote(AgentSentinels.DELEGATE_SESSION_SENTINEL)).length - 1 >= 2;
            if (inDeepDelegation) {
                log.infof(
                        "FINISH-HANDLER code-gen blocked in deep delegation chain (depth >= 2) for session %s — forcing final answer without re-delegation",
                        sessionId);
                String hint = "System: You have reached maximum delegation depth. "
                        + "You CANNOT delegate further. Provide your final response in plain text without code blocks, "
                        + "or summarize what was accomplished so far. Do NOT call the delegate tool.";
                return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
            }

            if (intentClassifier != null && intentClassifier.isSimpleConversational(goal)) {
                log.infof(
                        "FINISH-HANDLER simple chitchat in session %s for role '%s' attempted code generation; forcing plain-text correction without delegation",
                        sessionId, agentRole);
                String hint = "System: STOP. This request is a simple conversational greeting or message. "
                        + "You MUST respond ONLY with a short, friendly plain-text response (1-2 sentences). "
                        + "Do NOT output code blocks, JSON structures, or tool calls. Do NOT delegate.";
                return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
            }

            Map<String, Object> args = new HashMap<>();
            args.put(
                    "agent",
                    AgentRoleEnum.CODER.definition().aliases().stream()
                            .findFirst()
                            .orElse(AgentRoleEnum.CODER.id()));
            args.put("goal", buildCoderDelegationGoal(goal, answer));
            return new FinishActionHandler.FinishOutcome.InjectToolCall(
                    ToolCapability.DELEGATE_TO_AGENT.toolName(), args, cleanStep);
        }
        return null;
    }

    private String buildCoderDelegationGoal(String goal, String blockedAnswer) {
        String baseGoal =
                goal != null && !goal.isBlank() ? goal.strip() : "Complete the user's code generation request.";
        return baseGoal + "\n\nThe coordinator attempted to answer directly but is not allowed to emit code. "
                + "Use the draft below only as context; produce the final code answer yourself.\n\n"
                + blockedAnswer.strip();
    }

    public String sanitizeErrorForUser(String answer, String sessionId) {
        if (answer == null) return null;
        if (sessionId != null && sessionId.contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) {
            return answer;
        }
        if (INTERNAL_JVM_ERROR_PATTERN.matcher(answer).find()) {
            log.errorf("INTERNAL-LEAK BLOCKED in session %s: detected JVM exception in answer", sessionId);
            return "I encountered an internal error while formatting the response. Please try again.";
        }
        return answer;
    }

    public FinishActionHandler.FinishOutcome handleLeak(
            String candidateAnswer,
            ReasoningStep step,
            List<ReasoningStep> history,
            int llmSchemaErrorCount,
            String sessionId,
            String goal) {

        boolean isLeak = qualityGuard.isRawToolCallLeak(candidateAnswer);
        if (isLeak && rateLimitTracker.isBackingOff("primary")) {
            log.warnf("FINISH-HANDLER rate-limited - stripping leak instead of retrying for session %s", sessionId);
            String stripped = qualityGuard.stripRawToolJson(candidateAnswer);
            AnswerGuardStrategy.AnswerGuardResult guardResult = qualityGuard.guard(stripped, history);
            String finalAnswer = guardResult.answer();
            AgentResult result = AgentResult.finished(finalAnswer, List.copyOf(history));
            return new FinishActionHandler.FinishOutcome.Completed(result, finalAnswer);
        }

        String rawThought = step.thought();
        String strippedThought =
                (rawThought != null && qualityGuard != null) ? qualityGuard.stripRawToolJson(rawThought) : rawThought;
        String cleanThought = strippedThought != null ? strippedThought : "";
        boolean isBrokenFragment = FENCE_ONLY_PATTERN.matcher(cleanThought).matches();
        if (isBrokenFragment) {
            log.warnf(
                    "FINISH-HANDLER: Detected broken JSON fragment in answer for session %s - treating as leak.",
                    sessionId);
        }

        var leakedCall = qualityGuard.extractLeakedToolCall(candidateAnswer);
        if (leakedCall.isPresent()) {
            var call = leakedCall.get();
            log.infof(
                    "LEAKED-TOOL-EXEC session %s: parsed leaked tool call '%s' from FINISH answer - executing directly",
                    sessionId, call.toolName());
            InfiniteLoopChatModel.getLastUsedSlot().ifPresent(InfiniteLoopChatModel::recordQualityFailure);
            ReasoningStep cleanStep = step.withObservation(cleanThought);
            return new FinishActionHandler.FinishOutcome.ExecuteLeakedTool(
                    call.toolName(), call.arguments(), cleanStep);
        }

        if ((isLeak || looksLikeJson(candidateAnswer)) && !containsToolCallKeys(candidateAnswer)) {
            boolean isSimpleChitchat = intentClassifier != null && intentClassifier.isSimpleConversational(goal);
            if (isSimpleChitchat) {
                boolean isGreeting = intentClassifier != null && intentClassifier.isGreeting(goal);
                String fallback = AgentSentinels.resolveConversationalFallback(isGreeting);
                if (llmSchemaErrorCount == 0 && correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                    log.warnf(
                            "FINISH-HANDLER session %s: JSON without tool-call keys in simple chitchat - retrying once",
                            sessionId);
                    ReasoningStep cleanStep =
                            step.withObservation("Your response was raw JSON instead of plain prose.");
                    String hint = "System: STOP. Your response was a JSON structure, NOT a natural language answer. "
                            + "This request is a simple conversational message. Answer ONLY in plain prose (1-2 sentences). "
                            + "Do NOT output JSON, code blocks, or tool calls.";
                    return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
                }
                log.warnf("FINISH-HANDLER session %s: forcing safe fallback after JSON in simple chitchat", sessionId);
                return new FinishActionHandler.FinishOutcome.Completed(
                        AgentResult.finished(fallback, List.copyOf(history)), fallback);
            }

            if (isThinkingJson(candidateAnswer)) {
                log.warnf("FINISH-HANDLER session %s: thinking-style JSON leaked into answer - retrying", sessionId);
                if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                    ReasoningStep cleanStep = step.withObservation("");
                    String hint = "System: Your response was a JSON thought structure, NOT a valid answer. "
                            + "You MUST respond in plain text only. "
                            + "Read the conversation history carefully and answer the user's question DIRECTLY "
                            + "in plain text using FINISH. Do NOT output JSON, tool calls, or structured data.";
                    return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
                }
                String stripped = qualityGuard.stripRawToolJson(candidateAnswer);
                if (!stripped.isBlank() && stripped.length() > SALVAGE_MIN_LENGTH) {
                    return new FinishActionHandler.FinishOutcome.Completed(
                            AgentResult.finished(stripped, List.copyOf(history)), stripped);
                }
            }

            if (looksLikeJson(candidateAnswer)) {
                log.warnf(
                        "FINISH-HANDLER session %s: raw JSON in answer without tool keys - retrying with prose hint",
                        sessionId);
                if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                    ReasoningStep cleanStep =
                            step.withObservation("Your response was raw JSON instead of natural language prose.");
                    String hint =
                            "System: STOP. Your response was a raw JSON structure, NOT a natural language answer. "
                                    + "You MUST respond in plain text prose. Do NOT output JSON structures, code blocks, or data formats. "
                                    + "Answer the user's question directly in natural language.";
                    return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
                }
            }

            log.infof("FINISH-HANDLER session %s: JSON without tool-call keys - guarding answer", sessionId);
            var guarded = qualityGuard.guard(candidateAnswer, history).answer();
            return new FinishActionHandler.FinishOutcome.Completed(
                    AgentResult.finished(guarded, List.copyOf(history)), guarded);
        }

        if ((llmSchemaErrorCount >= 1 || isBrokenFragment)
                && !cleanThought.isBlank()
                && !isBrokenFragment
                && cleanThought.length() > SALVAGE_MIN_LENGTH
                && !cleanThought.contains(SALVAGE_REJECT_PHRASE)
                && !looksLikeJson(cleanThought)
                && !qualityGuard.isRawToolCallLeak(cleanThought)) {
            log.warnf(
                    "FINISH-HANDLER early salvage after %d leaks for session %s - clean thought is usable",
                    llmSchemaErrorCount, sessionId);
            AnswerGuardStrategy.AnswerGuardResult guardResult = qualityGuard.guard(cleanThought, history);
            String finalAnswer = guardResult.answer();
            FinishActionHandler.FinishOutcome injected =
                    tryInjectOnRefusal(finalAnswer, goal, sessionId, step, history);
            if (injected != null) return injected;
            return new FinishActionHandler.FinishOutcome.Completed(
                    AgentResult.finished(finalAnswer, List.copyOf(history)), finalAnswer);
        }

        InfiniteLoopChatModel.getLastUsedSlot().ifPresent(InfiniteLoopChatModel::recordQualityFailure);
        if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
            log.warnf(
                    "STATE-MACHINE session %s FINISH answer contains raw tool call JSON - retrying (attempt %d)",
                    sessionId, llmSchemaErrorCount + 1);
            ReasoningStep cleanStep = step.withObservation(cleanThought);
            String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
            String severity = llmSchemaErrorCount >= 2 ? "CRITICAL" : "WARNING";
            String correctionHint = "System: " + severity + ". Your last response contained raw JSON/tool-call "
                    + "text instead of a proper FINISH answer. This is attempt " + (llmSchemaErrorCount + 1) + ". "
                    + "RULES: (1) NEVER write TOOLCALLS, raw JSON, or tool syntax in your FINISH answer text. "
                    + (leakedTool != null
                            ? "(2) If you need to call '" + leakedTool + "', use the TOOL CALLING API, not plain text. "
                            : "")
                    + "(3) If the information you need is already in the conversation history above, "
                    + "read it carefully and answer DIRECTLY in plain text using FINISH. "
                    + "Do NOT call any tools. Do NOT output JSON. Just answer the user's question.";
            return new FinishActionHandler.FinishOutcome.RetryLeak(cleanStep, correctionHint, leakedTool);
        }
        return salvage(candidateAnswer, history, sessionId);
    }

    private boolean containsToolCallKeys(String answer) {
        if (answer == null) return false;
        return answer.contains("\"name\"")
                || answer.contains("\"tool_name\"")
                || answer.contains("\"tool_calls\"")
                || answer.contains("\"function\"")
                || answer.contains("\"action\"")
                || answer.contains("\"parameters\"")
                || answer.contains("\"arguments\"")
                || answer.contains("TOOLCALLS");
    }

    private FinishActionHandler.FinishOutcome salvage(
            String candidateAnswer, List<ReasoningStep> history, String sessionId) {
        String salvaged = qualityGuard.stripRawToolJson(candidateAnswer);
        if (!salvaged.isBlank()
                && !FENCE_ONLY_PATTERN.matcher(salvaged).matches()
                && salvaged.length() > SALVAGE_MIN_LENGTH
                && !salvaged.contains(SALVAGE_REJECT_PHRASE)) {
            if (looksLikeJson(salvaged)) {
                log.errorf(
                        "STATE-MACHINE session %s exceeded schema limit. Salvaged thought is still JSON - failing turn.",
                        sessionId);
                AgentResult failed = AgentResult.failed(
                        "I encountered a formatting error (JSON echo) and could not complete your request.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history));
                return new FinishActionHandler.FinishOutcome.SalvageFailed(failed);
            }
            log.errorf(
                    "STATE-MACHINE session %s exceeded schema self-correction limit after raw tool call leak. "
                            + "Salvaging partially clean answer.",
                    sessionId);
            AgentResult result = AgentResult.finished(salvaged, List.copyOf(history));
            return new FinishActionHandler.FinishOutcome.Completed(result, salvaged);
        }

        log.errorf(
                "STATE-MACHINE session %s exceeded schema self-correction limit. No salvageable answer found.",
                sessionId);
        AgentResult failed = AgentResult.failed(
                "I encountered a formatting error and could not complete your request. Please try again.",
                AgentResult.FailureCategory.SCHEMA_ERROR,
                List.copyOf(history));
        return new FinishActionHandler.FinishOutcome.SalvageFailed(failed);
    }

    public boolean looksLikeJson(String text) {
        return textPatternUtils.looksLikeJson(text);
    }

    private boolean isThinkingJson(String text) {
        if (text == null) return false;
        String lower = text.strip().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("{")) return false;
        int thinkingKeys = 0;
        for (String key : List.of("thoughts", "reasoning", "plan", "criticism", "speak", "thought")) {
            if (lower.contains("\"" + key + "\"")) thinkingKeys++;
        }
        return thinkingKeys >= 2;
    }

    public boolean historyContainsSuccessfulSchedule(List<ReasoningStep> history) {
        return hallucinationDetector.historyContainsSuccessfulSchedule(history);
    }

    private String sanitizeAnswer(String raw) {
        if (raw == null) return null;
        String stripped = TOOL_CALL_JSON_LEAK.matcher(raw).replaceAll("").strip();
        if (!stripped.equals(raw)) {
            log.warnf(
                    "FINISH-HANDLER stripped leaked tool-call JSON from answer (%d chars removed)",
                    raw.length() - stripped.length());
        }
        String collapsed = HtmlArtifactUtil.collapseHtmlArtifact(stripped);
        if (!collapsed.equals(stripped)) {
            log.warnf("FINISH-HANDLER collapsed HTML artifact in answer");
        }
        return collapsed;
    }

    private FinishActionHandler.FinishOutcome tryInjectOnRefusal(
            String answer, String goal, String sessionId, ReasoningStep step, List<ReasoningStep> history) {
        if (goal == null || (intentClassifier != null && intentClassifier.isSimpleConversational(goal))) {
            log.debugf("TOOL-INJECT skip: goal is null or simple chitchat for session %s", sessionId);
            return null;
        }

        String availableTools = String.join(",", toolRegistry.listTools());
        boolean isRefusal = AgentSentinels.REFUSAL_PATTERN.matcher(answer).find();

        if (isRefusal && AgentSentinels.RESEARCH_KEYWORDS.matcher(goal).find()) {
            String searchTool = findSearchTool(availableTools);
            if (searchTool != null) {
                log.infof("TOOL-INJECT session %s: salvaged refusal detected, injecting %s", sessionId, searchTool);
                Map<String, Object> args = new HashMap<>();
                args.put(
                        "query",
                        goal.replaceAll("(?i)^(do )?research (on )?(use )?", "").trim());
                args.put("count", 5);
                return new FinishActionHandler.FinishOutcome.InjectToolCall(
                        searchTool,
                        args,
                        step.withObservation("Injecting search tool — salvaged answer was a refusal"));
            }
        }

        if (AgentSentinels.EXECUTION_KEYWORDS.matcher(goal).find()
                && availableTools.contains(ToolCapability.SANDBOX_EXEC.toolName())
                && !answer.contains("```")
                && !historyContainsSuccess(ToolCapability.SANDBOX_EXEC.toolName(), history)) {
            log.infof("TOOL-INJECT session %s: execution goal but no code in answer, forcing correction", sessionId);
            return new FinishActionHandler.FinishOutcome.Correction(
                    step.withObservation("Agent finished without writing code for an execution goal"),
                    "STOP. You MUST write a complete program and call sandbox_exec to execute it. "
                            + "The sandbox_exec tool requires 'language' (e.g. "
                            + CodeLanguageDetector.SUPPORTED_LANGUAGES_HINT
                            + ") and 'code' parameters. "
                            + "Do NOT explain. Do NOT research. Write the code NOW and call sandbox_exec immediately.");
        }
        return null;
    }

    private static boolean historyContainsSuccess(String toolName, List<ReasoningStep> history) {
        return history != null
                && history.stream()
                        .anyMatch(s -> s instanceof ReasoningStep.ToolCallStep tc
                                && toolName.equals(tc.action())
                                && Boolean.TRUE.equals(tc.success()));
    }
}
