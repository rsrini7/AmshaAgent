package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.regex.Pattern;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.util.LogUtil;
import com.arcclaw.core.util.TextPatternUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.text.StringEscapeUtils;
import org.jboss.logging.Logger;

public class FinishOutcomeProcessor {

    private static final Logger log = Logger.getLogger(FinishOutcomeProcessor.class);

    static final int SALVAGE_MIN_LENGTH = 5;

    static final String SALVAGE_REJECT_PHRASE = "completed the task";

    private static final Pattern INTERNAL_JVM_ERROR_PATTERN = Pattern.compile(
            "(?:java\\.lang\\.)?" +
            "(?:NullPointerException|IllegalArgumentException|" +
            "IllegalStateException|StackOverflowError|OutOfMemoryError)" +
            "(?::\\s.+)",
            Pattern.MULTILINE
    );

    private static final Pattern TOOL_CALL_JSON_LEAK =
            Pattern.compile("(?s)\\{\\s*\"(tool|action|function|callId)\"\\s*:.*?\\}", Pattern.DOTALL);

    private static final Pattern PREFIX_TOKEN_LEAK = Pattern.compile(
            "(?i)^(\\s*[*_]*(FINISH:?|Action:\\s*FINISH|Answer:|Final Answer:|Response:)[*_]*\\s*)+",
            Pattern.MULTILINE
    );

    private static final Pattern SUFFIX_TOKEN_LEAK = Pattern.compile(
            "(?i)\\s*[*_]*(FINISH:?|Action:\\s*FINISH)[*_]*\\s*$",
            Pattern.MULTILINE
    );

    private static final Pattern FENCE_ONLY_PATTERN = Pattern.compile(
            "^\\s*```[a-z]*\\s*[}\\]]*\\s*```\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final AnswerGuardStrategy qualityGuard;
    private final CodeGenerationPolicy codePolicy;
    private final RateLimitTracker rateLimitTracker;
    private final CorrectionStrategy correctionStrategy;
    private final ObjectMapper objectMapper;
    private final TextPatternUtils textPatternUtils;

    public FinishOutcomeProcessor(AnswerGuardStrategy qualityGuard,
                                  CodeGenerationPolicy codePolicy,
                                  RateLimitTracker rateLimitTracker,
                                  CorrectionStrategy correctionStrategy,
                                  ObjectMapper objectMapper,
                                  TextPatternUtils textPatternUtils) {
        this.qualityGuard = qualityGuard;
        this.codePolicy = codePolicy;
        this.rateLimitTracker = rateLimitTracker;
        this.correctionStrategy = correctionStrategy;
        this.objectMapper = objectMapper;
        this.textPatternUtils = textPatternUtils;
    }

    public String extractCandidateAnswer(ReasoningStep step) {
        String candidateAnswer = step.thought();
        if (step.actionInput() != null
                && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
            candidateAnswer = String.valueOf(
                    step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY));
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
        answer = answer
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0026", "&")
                .replace("\\u0027", "'");
        return sanitizeAnswer(answer);
    }

    public FinishActionHandler.FinishOutcome checkHallucination(String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        if (answer != null && answer.contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE)) {
            boolean actuallyScheduled = historyContainsSuccessfulSchedule(history);

            if (!actuallyScheduled) {
                log.warnf("FINISH-HANDLER: Hallucinated scheduling success detected for session %s. " +
                          "No 'schedule_task' call found in history.", sessionId);

                ReasoningStep cleanStep = step.withObservation("Detected hallucinated scheduling success in FINISH answer");
                String hint = "System: STOP. You claimed '" + AgentSentinels.SCHEDULE_SUCCESS_MESSAGE + "' but you did NOT call " +
                    "the schedule_task tool in this turn. You must invoke the schedule_task tool with " +
                    "the correct cron expression and goal BEFORE calling FINISH.";

                return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
            }
        }
        return null;
    }

    public FinishActionHandler.FinishOutcome applyCodePolicy(String sessionId, String agentRole, List<ReasoningStep> history, String answer) {
        if (answer != null && codePolicy.isGeneratedCodeBlock(answer, agentRole)
                && !codePolicy.canGenerateCode(agentRole)) {
            String refusal = codePolicy.buildRefusalMessage(agentRole);
            log.infof("FINISH-HANDLER code-gen blocked for role '%s' in session %s", agentRole, sessionId);
            AgentResult result = AgentResult.finished(refusal, List.copyOf(history));
            return new FinishActionHandler.FinishOutcome.Completed(result, refusal);
        }
        return null;
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

    public FinishActionHandler.FinishOutcome handleLeak(String candidateAnswer,
                                     ReasoningStep step,
                                     List<ReasoningStep> history,
                                     int llmSchemaErrorCount,
                                     String sessionId) {

        boolean isLeak = qualityGuard.isRawToolCallLeak(candidateAnswer);

        if (isLeak && rateLimitTracker.isBackingOff("primary")) {
            log.warnf("FINISH-HANDLER rate-limited - stripping leak instead of retrying for session %s", sessionId);
            String stripped = qualityGuard.stripRawToolJson(candidateAnswer);

            AnswerGuardStrategy.AnswerGuardResult guardResult = qualityGuard.guard(stripped, history);
            String finalAnswer = guardResult.answer();

            AgentResult result = AgentResult.finished(finalAnswer, List.copyOf(history));
            return new FinishActionHandler.FinishOutcome.Completed(result, finalAnswer);
        }

        String cleanThought = qualityGuard.stripRawToolJson(candidateAnswer);
        boolean isBrokenFragment = FENCE_ONLY_PATTERN.matcher(cleanThought).matches();

        if (isBrokenFragment) {
            log.warnf("FINISH-HANDLER: Detected broken JSON fragment in answer for session %s - treating as leak.", sessionId);
        }

        if ((llmSchemaErrorCount >= 1 || isBrokenFragment)
                && !cleanThought.isBlank()
                && !isBrokenFragment
                && cleanThought.length() > SALVAGE_MIN_LENGTH
                && !cleanThought.contains(SALVAGE_REJECT_PHRASE)) {

            if (looksLikeJson(cleanThought)) {
                if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                    log.warnf("FINISH-HANDLER salvaged thought is still JSON for session %s - retrying with direct-answer hint", sessionId);
                    ReasoningStep cleanStep = step.withObservation("");
                    String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
                    String directHint = "System: Your response was raw JSON, NOT a valid answer. " +
                            "You are NOT allowed to write tool calls as text. " +
                            "The information the user asked for IS in the conversation history above. " +
                            "Read the history carefully and answer DIRECTLY in plain text using FINISH. " +
                            "Do NOT call any tools. Do NOT output JSON.";
                    return new FinishActionHandler.FinishOutcome.RetryLeak(cleanStep, directHint, leakedTool);
                }

                log.warnf("FINISH-HANDLER salvaged thought is still JSON - not usable, failing turn for session %s", sessionId);
                AgentResult failed = AgentResult.failed(
                        "I encountered a formatting error (JSON echo) and could not complete your request.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history));
                return new FinishActionHandler.FinishOutcome.SalvageFailed(failed);
            }

            log.warnf("FINISH-HANDLER early salvage after %d leaks for session %s - clean thought is usable",
                    llmSchemaErrorCount, sessionId);

            AnswerGuardStrategy.AnswerGuardResult guardResult = qualityGuard.guard(cleanThought, history);
            String finalAnswer = guardResult.answer();

            return new FinishActionHandler.FinishOutcome.Completed(
                    AgentResult.finished(finalAnswer, List.copyOf(history)), finalAnswer);
        }

        if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
            log.warnf("STATE-MACHINE session %s FINISH answer contains raw tool call JSON - retrying (attempt %d)",
                    sessionId, llmSchemaErrorCount + 1);

            ReasoningStep cleanStep = step.withObservation(cleanThought);

            String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
            String correctionHint = "System: Your last response was a raw tool call JSON or TOOLCALLS format "
                    + "written as plain text, not a valid FINISH answer. You MUST invoke tools through the tool "
                    + "mechanism - NEVER write TOOLCALLS or raw JSON in your answer text. Retry now: call "
                    + leakedTool + " properly using the tool calling API, or FINISH with a plain text answer.";

            return new FinishActionHandler.FinishOutcome.RetryLeak(cleanStep, correctionHint, leakedTool);
        }

        return salvage(candidateAnswer, history, sessionId);
    }

    private FinishActionHandler.FinishOutcome salvage(String candidateAnswer,
                                  List<ReasoningStep> history,
                                  String sessionId) {
        String salvaged = qualityGuard.stripRawToolJson(candidateAnswer);

        if (!salvaged.isBlank()
                && !FENCE_ONLY_PATTERN.matcher(salvaged).matches()
                && salvaged.length() > SALVAGE_MIN_LENGTH
                && !salvaged.contains(SALVAGE_REJECT_PHRASE)) {

            if (looksLikeJson(salvaged)) {
                log.errorf("STATE-MACHINE session %s exceeded schema limit. Salvaged thought is still JSON - failing turn.", sessionId);
                AgentResult failed = AgentResult.failed(
                        "I encountered a formatting error (JSON echo) and could not complete your request.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history));
                return new FinishActionHandler.FinishOutcome.SalvageFailed(failed);
            }

            log.errorf("STATE-MACHINE session %s exceeded schema self-correction limit after raw tool call leak. "
                    + "Salvaging partially clean answer.", sessionId);
            AgentResult result = AgentResult.finished(salvaged, List.copyOf(history));
            return new FinishActionHandler.FinishOutcome.Completed(result, salvaged);
        }

        log.errorf("STATE-MACHINE session %s exceeded schema self-correction limit. No salvageable answer found.",
                sessionId);
        AgentResult failed = AgentResult.failed(
                "I encountered a formatting error and could not complete your request. Please try again.",
                AgentResult.FailureCategory.SCHEMA_ERROR,
                List.copyOf(history));
        return new FinishActionHandler.FinishOutcome.SalvageFailed(failed);
    }

    private boolean looksLikeJson(String text) {
        return textPatternUtils.looksLikeJson(text);
    }

    private boolean historyContainsSuccessfulSchedule(List<ReasoningStep> history) {
        return history.stream().anyMatch(this::stepContainsSuccessfulSchedule);
    }

    private boolean stepContainsSuccessfulSchedule(ReasoningStep step) {
        return switch (step) {
            case ReasoningStep.ToolCallStep toolCall -> "schedule_task".equals(toolCall.action()) && Boolean.TRUE.equals(toolCall.success());
            case ReasoningStep.ParallelStep parallel -> {
                Object batchRaw = parallel.actionInput().get("batch");
                if (!(batchRaw instanceof String batchJson) || batchJson.isBlank()) yield false;
                try {
                    List<ParallelToolCall> calls = objectMapper.readValue(batchJson, new TypeReference<List<ParallelToolCall>>() {});
                    yield calls.stream().anyMatch(call -> "schedule_task".equals(call.toolName()) && call.success());
                } catch (Exception e) {
                    log.debugf("FINISH-HANDLER: failed to parse parallel batch: %s", LogUtil.rootMessage(e));
                    yield false;
                }
            }
            default -> false;
        };
    }

    private String sanitizeAnswer(String raw) {
        if (raw == null) return null;
        String stripped = TOOL_CALL_JSON_LEAK.matcher(raw).replaceAll("").strip();
        if (!stripped.equals(raw)) {
            log.warnf("FINISH-HANDLER stripped leaked tool-call JSON from answer (%d chars removed)",
                    raw.length() - stripped.length());
        }
        return stripped;
    }
}
