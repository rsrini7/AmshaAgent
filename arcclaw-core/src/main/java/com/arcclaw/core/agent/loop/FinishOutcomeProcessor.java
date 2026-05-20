package com.arcclaw.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.routing.InfiniteLoopChatModel;
import com.arcclaw.core.tools.ToolRegistry;
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

    /** Detects claims of having stored/saved/remembered user information without an actual tool call. */
    private static final Pattern HALLUCINATED_STORAGE_CLAIM = Pattern.compile(
            "(?i)\\b(?:I (?:have |'ve )?(?:stored|saved|remembered|noted|recorded|kept)|(?:your|the) (?:name|identity|interest|preference|information) (?:is|has been|was) (?:stored|saved|remembered|noted))\\b",
            Pattern.MULTILINE
    );

    /** Detects claims of having scheduled a task/reminder/timer without an actual tool call. */
    private static final Pattern HALLUCINATED_SCHEDULE_CLAIM = Pattern.compile(
            "(?i)\\b(?:I (?:have |'ve )?(?:scheduled|set a (?:reminder|timer)|created a (?:cron|task))|task (?:is |has been )?scheduled|scheduled the task|cron job has been|will run (?:in|at|every)|remind you in|reminder has been set|timer has been set)\\b",
            Pattern.MULTILINE
    );

    /** Detects knowledge disclaimers when memory_search results should have been synthesized. */
    private static final Pattern KNOWLEDGE_DISCLAIMER = Pattern.compile(
            "(?i)(?:I (?:do(?:n't| not) (?:have|know|possess|store|remember|recall)|(?:have|possess) (?:no|not)) (?:any )?(?:information|data|knowledge|record|detail|identity|name|preference)|(?:no|not? any) (?:information|data|knowledge|record) (?:about|regarding|on|of) (?:you|the user|their))",
            Pattern.MULTILINE
    );

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

    private static final List<String> SEARCH_TOOL_NAMES = List.of(
            ToolCapability.WEB_SEARCH_SERPER.toolName(),
            ToolCapability.WEB_SEARCH_BRAVE.toolName(),
            "web_search", "search");

    private final AnswerGuardStrategy qualityGuard;
    private final CodeGenerationPolicy codePolicy;
    private final RateLimitTracker rateLimitTracker;
    private final CorrectionStrategy correctionStrategy;
    private final ObjectMapper objectMapper;
    private final TextPatternUtils textPatternUtils;
    private final ToolRegistry toolRegistry;

    public FinishOutcomeProcessor(AnswerGuardStrategy qualityGuard,
                                  CodeGenerationPolicy codePolicy,
                                  RateLimitTracker rateLimitTracker,
                                  CorrectionStrategy correctionStrategy,
                                  ObjectMapper objectMapper,
                                  TextPatternUtils textPatternUtils,
                                  ToolRegistry toolRegistry) {
        this.qualityGuard = qualityGuard;
        this.codePolicy = codePolicy;
        this.rateLimitTracker = rateLimitTracker;
        this.correctionStrategy = correctionStrategy;
        this.objectMapper = objectMapper;
        this.textPatternUtils = textPatternUtils;
        this.toolRegistry = toolRegistry;
    }

    private String findSearchTool(String availableTools) {
        for (String name : SEARCH_TOOL_NAMES) {
            if (availableTools.contains(name)) return name;
        }
        return null;
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
        if (answer != null) {
            boolean hasScheduleClaim = answer.contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE)
                    || HALLUCINATED_SCHEDULE_CLAIM.matcher(answer).find();

            if (hasScheduleClaim) {
                boolean isNegative = answer.toLowerCase().contains("not scheduled")
                        || answer.toLowerCase().contains("cannot schedule")
                        || answer.toLowerCase().contains("can't schedule")
                        || answer.toLowerCase().contains("failed to schedule")
                        || answer.toLowerCase().contains("unable to schedule");

                if (!isNegative) {
                    boolean actuallyScheduled = historyContainsSuccessfulSchedule(history);
                    if (!actuallyScheduled) {
                        log.warnf("FINISH-HANDLER: Hallucinated scheduling success detected for session %s. " +
                                  "No 'schedule_task' call found in history. Answer: %s", sessionId, answer);

                        ReasoningStep cleanStep = step.withObservation("Detected hallucinated scheduling success in FINISH answer");
                        String hint;
                        if (answer.contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE)) {
                            hint = "System: STOP. You claimed '" + AgentSentinels.SCHEDULE_SUCCESS_MESSAGE +
                                "' but you did NOT call the schedule_task tool. You must invoke the schedule_task tool with " +
                                "the correct cron expression and goal BEFORE calling FINISH.";
                        } else {
                            hint = "System: STOP. You claimed to have scheduled/set a reminder but you did NOT call " +
                                "the schedule_task tool. You must invoke the schedule_task tool with " +
                                "the correct cron expression and goal BEFORE calling FINISH.";
                        }

                        return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
                    }
                }
            }
        }

        // Detect hallucinated memory storage claims
        if (answer != null && HALLUCINATED_STORAGE_CLAIM.matcher(answer).find()) {
            boolean actuallyStored = historyContainsIngestDocument(history);
            if (!actuallyStored) {
                log.warnf("FINISH-HANDLER: Hallucinated memory storage claim detected for session %s. " +
                          "No 'ingest_document' call found in history.", sessionId);

                ReasoningStep cleanStep = step.withObservation("Detected hallucinated memory storage claim in FINISH answer");
                String hint = "System: STOP. You claimed to have stored/remembered user information but you did NOT call " +
                    "the ingest_document tool. You CANNOT store information without calling ingest_document. " +
                    "Either: (1) Call ingest_document NOW with the information, or (2) Answer honestly that " +
                    "you have NOT stored anything. NEVER claim a tool action you did not perform.";

                return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
            }
        }

        // Detect memory_search synthesis failure: tool returned results but LLM disclaims knowledge
        if (answer != null && KNOWLEDGE_DISCLAIMER.matcher(answer).find()) {
            String memoryResult = findMemorySearchResult(history);
            if (memoryResult != null) {
                log.warnf("FINISH-HANDLER: Memory search synthesis failure detected for session %s. " +
                          "memory_search returned results but answer disclaims knowledge.", sessionId);

                ReasoningStep cleanStep = step.withObservation("Detected memory_search synthesis failure");
                String hint = "System: STOP. You just searched your memory and found information about this user. " +
                    "Your memory_search tool returned these results:\n---\n" +
                    memoryResult + "\n---\n" +
                    "You MUST synthesize these results into a natural, conversational answer. " +
                    "Do NOT say 'I don't have information' when your memory search just returned it. " +
                    "Read the tool results above and answer the user directly.";

                return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
            }
        }

        return null;
    }

    public FinishActionHandler.FinishOutcome applyCodePolicy(String sessionId, String agentRole, List<ReasoningStep> history, String answer, ReasoningStep step) {
        if (answer != null && codePolicy.isGeneratedCodeBlock(answer, agentRole)
                && !codePolicy.canGenerateCode(agentRole)) {
            log.infof("FINISH-HANDLER code-gen blocked for role '%s' in session %s", agentRole, sessionId);

            ReasoningStep cleanStep = step.withObservation("Code generation blocked for " + agentRole);
            String hint = "System: You attempted to output code in your FINISH answer, but your role (" + agentRole
                    + ") cannot generate code. " + codePolicy.buildRefusalMessage(agentRole)
                    + " Do NOT tell the user to ask them separately. "
                    + "Instead, use the `delegate_to_agent` tool NOW with agent='coder' and a complete goal "
                    + "that includes the full code generation, execution, and result-checking request. "
                    + "Wait for coder's response, then FINISH with the result.";

            return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
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

        String cleanThought = qualityGuard.stripRawToolJson(candidateAnswer);
        boolean isBrokenFragment = FENCE_ONLY_PATTERN.matcher(cleanThought).matches();

        if (isBrokenFragment) {
            log.warnf("FINISH-HANDLER: Detected broken JSON fragment in answer for session %s - treating as leak.", sessionId);
        }

        // Try to parse the leaked tool call FIRST — before early salvage strips it
        var leakedCall = qualityGuard.extractLeakedToolCall(candidateAnswer);
        if (leakedCall.isPresent()) {
            var call = leakedCall.get();
            log.infof("LEAKED-TOOL-EXEC session %s: parsed leaked tool call '%s' from FINISH answer - executing directly",
                    sessionId, call.toolName());
            InfiniteLoopChatModel.getLastUsedSlot().ifPresent(InfiniteLoopChatModel::recordQualityFailure);
            ReasoningStep cleanStep = step.withObservation(cleanThought);
            return new FinishActionHandler.FinishOutcome.ExecuteLeakedTool(call.toolName(), call.arguments(), cleanStep);
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

            FinishActionHandler.FinishOutcome injected = tryInjectOnRefusal(finalAnswer, goal, sessionId, step);
            if (injected != null) return injected;

            return new FinishActionHandler.FinishOutcome.Completed(
                    AgentResult.finished(finalAnswer, List.copyOf(history)), finalAnswer);
        }

        // Record quality failure for the slot that produced this leak
        InfiniteLoopChatModel.getLastUsedSlot().ifPresent(InfiniteLoopChatModel::recordQualityFailure);

        if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
            log.warnf("STATE-MACHINE session %s FINISH answer contains raw tool call JSON - retrying (attempt %d)",
                    sessionId, llmSchemaErrorCount + 1);

            ReasoningStep cleanStep = step.withObservation(cleanThought);

            String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
            String severity = llmSchemaErrorCount >= 2 ? "CRITICAL" : "WARNING";
            String correctionHint = "System: " + severity + ". Your last response contained raw JSON/tool-call "
                    + "text instead of a proper FINISH answer. This is attempt " + (llmSchemaErrorCount + 1) + ". "
                    + "RULES: (1) NEVER write TOOLCALLS, raw JSON, or tool syntax in your FINISH answer text. "
                    + (leakedTool != null ? "(2) If you need to call '" + leakedTool + "', use the TOOL CALLING API, not plain text. " : "")
                    + "(3) If the information you need is already in the conversation history above, "
                    + "read it carefully and answer DIRECTLY in plain text using FINISH. "
                    + "Do NOT call any tools. Do NOT output JSON. Just answer the user's question.";

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

    public boolean historyContainsSuccessfulSchedule(List<ReasoningStep> history) {
        return history.stream().anyMatch(this::stepContainsSuccessfulSchedule);
    }

    private boolean historyContainsIngestDocument(List<ReasoningStep> history) {
        return history.stream().anyMatch(step -> step instanceof ReasoningStep.ToolCallStep tc
                && "ingest_document".equals(tc.action())
                && Boolean.TRUE.equals(tc.success()));
    }

    private String findMemorySearchResult(List<ReasoningStep> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ReasoningStep step = history.get(i);
            if (step instanceof ReasoningStep.ToolCallStep tc
                    && ToolCapability.MEMORY_SEARCH.toolName().equals(tc.action())
                    && Boolean.TRUE.equals(tc.success())
                    && tc.observation() != null
                    && !tc.observation().isBlank()) {
                return tc.observation();
            }
        }
        return null;
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

    /**
     * If the agent finished without using the right tool, inject it.
     * Handles two cases:
     * 1. Refusal + research goal → inject search tool
     * 2. Execution goal + no code in answer + sandbox available → inject sandbox_exec
     */
    private FinishActionHandler.FinishOutcome tryInjectOnRefusal(String answer, String goal,
                                                                  String sessionId, ReasoningStep step) {
        if (goal == null) {
            log.debugf("TOOL-INJECT skip: goal is null for session %s", sessionId);
            return null;
        }

        String availableTools = String.join(",", toolRegistry.listTools());
        boolean isRefusal = AgentSentinels.REFUSAL_PATTERN.matcher(answer).find();

        // Case 1: Refusal + research goal → inject search
        if (isRefusal && AgentSentinels.RESEARCH_KEYWORDS.matcher(goal).find()) {
            String searchTool = findSearchTool(availableTools);
            if (searchTool != null) {
                log.infof("TOOL-INJECT session %s: salvaged refusal detected, injecting %s", sessionId, searchTool);
                Map<String, Object> args = new HashMap<>();
                args.put("query", goal.replaceAll("(?i)^(do )?research (on )?(use )?", "").trim());
                args.put("count", 5);
                return new FinishActionHandler.FinishOutcome.InjectToolCall(searchTool, args,
                        step.withObservation("Injecting search tool — salvaged answer was a refusal"));
            }
        }

        // Case 2: Execution goal + no code in answer + sandbox available → retry with correction
        if (AgentSentinels.EXECUTION_KEYWORDS.matcher(goal).find()
                && availableTools.contains(ToolCapability.SANDBOX_EXEC.toolName())
                && !answer.contains("```")) {
            log.infof("TOOL-INJECT session %s: execution goal but no code in answer, forcing correction", sessionId);
            return new FinishActionHandler.FinishOutcome.Correction(
                    step.withObservation("Agent finished without writing code for an execution goal"),
                    "STOP. You MUST write a complete program and call sandbox_exec to execute it. " +
                    "The sandbox_exec tool requires 'language' (e.g. 'java') and 'code' parameters. " +
                    "Do NOT explain. Do NOT research. Write the code NOW and call sandbox_exec immediately.");
        }

        return null;
    }
}
