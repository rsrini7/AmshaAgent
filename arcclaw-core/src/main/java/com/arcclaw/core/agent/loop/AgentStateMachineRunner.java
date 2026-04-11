package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.event.AgentStateChangedEvent;
import com.arcclaw.core.agent.persona.Agent;
import com.arcclaw.core.agent.persona.AgentRole;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.security.InputSanitizer;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.apache.commons.text.StringEscapeUtils;
import org.jboss.logging.Logger;

/**
 * Orchestrates the Thought-Plan-Act-Observe loop.
 * Step-level processing is delegated to {@link AgentStepProcessor}.
 */
@ApplicationScoped
public class AgentStateMachineRunner {
    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    // ── Raw tool-call leak guard ──────────────────────────────────────────────
    private static final Pattern RAW_TOOL_CALL_PATTERN = Pattern.compile(
        "^\\s*\\{\\s*\"type\"\\s*:\\s*\"function\"", Pattern.DOTALL);

    private static final Pattern RAW_DELEGATE_CALL_PATTERN =
        Pattern.compile( "(?m)^\\s*delegate_to_agent\\s*\\(\\s*agent\\s*=", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAW_TOOL_JSON_PATTERN = 
        Pattern.compile("^\\s*\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"parameters\"\\s*:", Pattern.DOTALL);

    // ── Suspiciously short non-answers: "Done.", "OK.", "ok" etc.
    private static final Pattern SUSPICIOUSLY_SHORT =
            Pattern.compile(
                "^[✅⏳\\s]*(done\\.?|ok\\.?)\\s*$",
                Pattern.CASE_INSENSITIVE
            );


    // ── FIX Bug 1: Minimum meaningful answer length ───────────────────────────
    // Garbage 2-token responses like "'." or "." contain no alphabetic characters.
    // Compaction race + thin LLM context produces these. Catch anything under this
    // length that has zero alphabetic content and route to retry prompt.
    private static final int MIN_ANSWER_ALPHA_LENGTH = 3;

    private static final int LOG_GOAL_MAX = 80;
    private static final String GOAL_LOG_FMT =
            "StateMachine starting session=%s goal=%s maxIterations=%d";

    @Inject EngineStepHandler engineStepHandler;
    @Inject ToolStepHandler toolStepHandler;
    @Inject ChatMemoryWriter writer;
    @Inject Instance<AgentObserver> observers;
    @Inject @AgentRole("synthesizer") Agent synthesizerAgent;
    @Inject SkillRegistry skillRegistry;
    @Inject @InputSanitizer.PII InputSanitizer piiScanReflex;
    @Inject AgentOtelContext agentOtelContext;
    @Inject Event<AgentStateChangedEvent> stateChangedEvent;
    @Inject StatefulAgentStore agentStore;
    @Inject CliResultBridge cliResultBridge;
    @Inject IntentClassificationService intentClassifier;
    @Inject RateLimitTracker rateLimitTracker;

    public AgentResult run(AgentExecutionContext context, ReasoningEngine engine,
                           List<ReasoningStep> resumedHistory) {
        List<ReasoningStep> history = new ArrayList<>(resumedHistory);
        int iteration = 0;
        int llmSchemaErrorCount = 0;
        int consecutiveFailures = 0;
        String transientError = null;

        if (log.isInfoEnabled()) {
            String raw = context.goal() != null ? context.goal() : "";
            String sanitized = piiScanReflex != null ? piiScanReflex.sanitize(raw) : raw;
            if (sanitized == null) sanitized = raw;
            
            String preview = sanitized.length() > LOG_GOAL_MAX
                    ? sanitized.substring(0, LOG_GOAL_MAX) + "…"
                    : sanitized;
            log.infof(GOAL_LOG_FMT, context.sessionId(), preview, context.maxIterations());
        }

        // Fire initial status event §78.4
        stateChangedEvent.fire(new AgentStateChangedEvent(context.sessionId(), context.agentRole(), "active", context.goal()));

        writer.saveGoal(context.sessionId(), context.goal());

        while (iteration < context.maxIterations()) {
            iteration++;
            Span iterSpan = agentOtelContext.startIterationSpan(
                    context.sessionId(), iteration, context.agentRole());
            try (Scope scope = iterSpan.makeCurrent()) {
                log.debugf("StateMachine iteration %d/%d", iteration, context.maxIterations());

                // Inject write-intent hint as transientError on iteration 1
                if (iteration == 1 && intentClassifier.isWriteIntent(context.goal())) {
                    transientError = "The user wants to store data. Call ingest_document immediately. " +
                                     "Pass [REDACTED-*] placeholders as-is. No explanation needed.";
                }

                StepDecision engineDecision;
                try {
                    engineDecision = engineStepHandler.processEngineStep(
                            context, engine, history, transientError, llmSchemaErrorCount, iteration);
                } catch (StructuredOutputException soe) {
                    log.warnf(
                        "StateMachine schema self-correction exhausted at iteration %d/%d for session %s — " +
                        "treating as loop exceeded to preserve LOOP_MAX_ITERATIONS audit semantics.",
                        iteration, context.maxIterations(), context.sessionId()
                    );
                    AgentResult res = AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
                    maybeSignalBridge(context, res);
                    return res;
                }

                if (engineDecision.type() == DecisionType.TERMINAL) {
                    fireTerminalEvent(context.sessionId(), context.agentRole(), context.goal(), engineDecision.result());
                    maybeSignalBridge(context, engineDecision.result());
                    return engineDecision.result();
                }
                if (engineDecision.type() == DecisionType.CONTINUE) {
                    transientError = engineDecision.transientError();
                    llmSchemaErrorCount = engineDecision.llmSchemaErrorCount();
                    continue;
                }

                ReasoningStep step = engineDecision.step();
                transientError = null;

                if (ReasoningStep.FINISH.equals(step.action())) {
                    if (step.isParallelBatch()) {
                        log.errorf("[PARALLEL] INVARIANT VIOLATION: session=%s parallel batch " +
                                   "step reached FINISH handler — this is a bug in Phase 70.",
                                   context.sessionId());
                    }

                    String candidateAnswer = step.thought();
                    if (step.actionInput() != null && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
                        candidateAnswer = String.valueOf(step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY));
                    }

                    if (isRawToolCallLeak(candidateAnswer)) {
                        if (RAW_TOOL_JSON_PATTERN.matcher(candidateAnswer).find() && rateLimitTracker.isBackingOff("primary")) {
                            log.warnf("FINISH answer has raw tool JSON but rate-limited — stripping instead of retrying for session %s", context.sessionId());
                            candidateAnswer = stripRawToolJson(candidateAnswer);
                            AgentResult result = AgentResult.finished(candidateAnswer, List.copyOf(history));
                            maybeSignalBridge(context, result);
                            return result;
                        }

                        log.warnf("[STATE-MACHINE] session %s: FINISH answer contains raw " +
                                  "tool call JSON — retrying with correction (schema attempt %d)",
                                  context.sessionId(), llmSchemaErrorCount + 1);
                        history.add(step);
                        String leakedTool = "the intended tool";
                        Matcher nameMatcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(candidateAnswer);
                        if (nameMatcher.find()) {
                            leakedTool = nameMatcher.group(1);
                        } else if (candidateAnswer.contains("delegate_to_agent")) {
                            leakedTool = "delegate_to_agent";
                        }

                        transientError =
                                "System: Your last response was a raw JSON tool call object " +
                                "written as plain text, not a valid FINISH answer. " +
                                "You MUST invoke tools through the tool mechanism — " +
                                "NEVER write {\"type\": \"function\", ...} or raw JSON as your answer text. " +
                                "Retry now: call " + leakedTool + " properly using the tool calling API, or FINISH with a plain text answer.";
                        llmSchemaErrorCount++;
                        if (llmSchemaErrorCount >= 2) {
                            log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction " +
                                       "limit after raw tool call leak", context.sessionId());
                            return AgentResult.failed(
                                    "I encountered a formatting error and could not complete your request. " +
                                    "Please try again.",
                                    AgentResult.FailureCategory.SCHEMA_ERROR,
                                    List.copyOf(history));
                        }
                        continue;
                    }

                    AgentResult result = handleFinishAction(context.sessionId(), context.agentRole(),
                            history, step, iteration);
                    maybeSignalBridge(context, result);
                    return result;
                }

                StepDecision validationDecision = toolStepHandler.validateTool(
                        context, history, step, consecutiveFailures);
                if (validationDecision.type() == DecisionType.TERMINAL) {
                    fireTerminalEvent(context.sessionId(), context.agentRole(), context.goal(), validationDecision.result());
                    maybeSignalBridge(context, validationDecision.result());
                    return validationDecision.result();
                }
                if (validationDecision.type() == DecisionType.CONTINUE) {
                    consecutiveFailures = validationDecision.consecutiveFailures();
                    continue;
                }
                step = validationDecision.step();

                // Re-dispatch if validateTool rewrote a hallucinated tool → FINISH
                if (ReasoningStep.FINISH.equals(step.action())) {
                    AgentResult result =  handleFinishAction(context.sessionId(), context.agentRole(), history, step, iteration);
                    maybeSignalBridge(context, result);
                    return result;
                }

                // skillSnapshot fetched lazily — only when a real tool invocation is about to happen.
                // FINISH, CONTINUE, and schema-retry paths never pay this cost.
                List<CompiledSkillEntry> skillSnapshot = skillRegistry.currentSnapshot();

                // B2 (F22): Guard condition to ensure approval was formally flushed.
                // Uses hot-path status cache to avoid DB read pressure.
                if (context.isStatusDirty()) {
                    Optional<AgentCheckpoint> cpOpt = agentStore.load(context.sessionId(), context.agentRole());
                    context.setLastCheckpointStatus(cpOpt.map(AgentCheckpoint::getStatus).orElse(null));
                }

                if (context.lastCheckpointStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                    log.warnf("Session %s is still AWAITING_APPROVAL. Refusing to invoke tool until state is flushed as IN_PROGRESS.", context.sessionId());
                    return AgentResult.failed("State corrupted: tool execution attempted before approval persistence completed.", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                }

                StepDecision invocationDecision = toolStepHandler.invokeToolAndObserve(
                        context, history, step, consecutiveFailures, skillSnapshot);

                if (invocationDecision.result() instanceof AgentResult.AwaitingApproval) {
                    context.markStatusDirty(); // Status will change to AWAITING_APPROVAL
                }
                if (invocationDecision.type() == DecisionType.TERMINAL) {
                    fireTerminalEvent(context.sessionId(), context.agentRole(), context.goal(), invocationDecision.result());
                    maybeSignalBridge(context, invocationDecision.result());
                    return invocationDecision.result();
                }
                consecutiveFailures = invocationDecision.consecutiveFailures();

            } catch (Exception e) {
                iterSpan.recordException(e);
                iterSpan.setStatus(StatusCode.ERROR, AgentErrorMessageUtil.safeMessage(e));
                throw e;
            } finally {
                iterSpan.end();
            }
        }

        log.warnf("StateMachine exceeded %d iterations for session=%s",
                context.maxIterations(), context.sessionId());
        AgentResult loopRes = AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
        fireTerminalEvent(context.sessionId(), context.agentRole(), context.goal(), loopRes);
        maybeSignalBridge(context, loopRes);
        return loopRes;
    }

    private void maybeSignalBridge(AgentExecutionContext context, AgentResult result) {
        if (context.sessionId().contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) return;
        if (result instanceof AgentResult.AwaitingApproval) return;
        // CLI sessions use the bridge to pass results back to blocking waits; other 
        // channels (Telegram, WebSocket) deliver via their specific listeners.
        if (ChannelType.CLI.id().equalsIgnoreCase(context.channelType())) {
            cliResultBridge.complete(context.sessionId(), result);
        }
    }

    private void fireTerminalEvent(String sessionId, String role, String goal, AgentResult result) {
        String status = switch (result) {
            case AgentResult.Finished _ -> "idle";
            case AgentResult.StructuredFinished _ -> "idle";
            case AgentResult.AwaitingApproval _ -> "waiting_approval";
            case AgentResult.AwaitingRetry _ -> "waiting_retry";
            default -> "error";
        };
        stateChangedEvent.fire(new AgentStateChangedEvent(sessionId, role, status, goal));
    }

    private boolean isRawToolCallLeak(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return RAW_TOOL_CALL_PATTERN.matcher(answer).find()
            || RAW_DELEGATE_CALL_PATTERN.matcher(answer).find()
            || RAW_TOOL_JSON_PATTERN.matcher(answer).find();
    }

    private String stripRawToolJson(String answer) {
        if (answer == null) return "";
        Matcher m = RAW_TOOL_JSON_PATTERN.matcher(answer);
        if (m.find()) {
            String stripped = answer.substring(m.end())
                .replaceFirst("^[^}]*\\}", "")
                .replaceAll("^[}\\s]+", "")
                .strip();
            return stripped.isBlank() ? "I have completed the task." : stripped;
        }
        return answer;
    }

    private AgentResult handleFinishAction(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step, int iteration) {
        log.infof("Agent reached FINISH at iteration %d", iteration);
 
        // NS3: Null-safe actionInput map dereference
        Optional<Map<String, Object>> inputOpt = Optional.ofNullable(step.actionInput());
        if (inputOpt.isEmpty()) {
            log.errorf("LLM sent FINISH without actionInput map for session %s", sessionId);
            return AgentResult.failed("Malformed LLM response: FINISH action missing input payload.",
                    AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history));
        }

        String answer = step.thought();
        if (inputOpt.get().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
            answer = String.valueOf(inputOpt.get().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY));
        }

        if (answer != null) {
            answer = StringEscapeUtils.unescapeHtml4(answer);
            answer = answer.replace("\\u003c", "<")
                          .replace("\\u003e", ">")
                          .replace("\\u0026", "&")
                          .replace("\\u0027", "'");
        }

        answer = sanitizeErrorForUser(answer, sessionId);
        answer = AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer);

        if (answer != null && isGeneratedCodeBlock(answer, agentRole)) {
            if (!AgentRoleEnum.fromString(agentRole).definition().canGenerateCode()) {
                String refusal = "I'm " + agentRole + " — I don't generate code. " +
                        "Please ask " + AgentRoleEnum.CODER.definition().displayName() + " ("+AgentRoleEnum.CODER.id()+") for code generation tasks.";
                writer.saveAnswer(sessionId, refusal);
                history.add(step.withObservation("Blocked: code generation by non-coder role"));
                return AgentResult.finished(refusal, List.copyOf(history));
            }
        }

        answer = guardFinalAnswer(answer, step.outputTokens(), sessionId);

        if (sessionId != null && answer != null && !answer.isBlank()) {
            writer.saveAnswer(sessionId, answer);
        }

        final ReasoningStep completedStep = step.withObservation("Task completed.");
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

        if (AgentRoleEnum.SYNTHESIZER.toScopedName().equals(agentRole)) {
            String combinedContext = history.stream()
                    .filter(s -> s.observation() != null
                            && !s.observation().isBlank()
                            && (s.success() == null || s.success())
                            && !"Task completed.".equals(s.observation()))
                    .map(s -> "Thought: " + s.thought() + "\nFinding: " + s.observation())
                    .collect(Collectors.joining("\n\n"));

            if (combinedContext.isBlank()) {
                return AgentResult.finished(answer, List.copyOf(history));
            }

            return synthesizerAgent.synthesize(sessionId, combinedContext, history);
        }
 
        stateChangedEvent.fire(new AgentStateChangedEvent(sessionId, agentRole, "idle", null));
        return AgentResult.finished(answer, List.copyOf(history));
    }

    /**
     * Suppresses bad final answers:
     *
     * 1. null/blank — always retry prompt.
     * 2. Suspiciously short non-answers ("Done.", "OK.") — compaction race.
     * 3. Punctuation-only garbage ("'.", ".") under MIN_ANSWER_ALPHA_LENGTH chars
     *    with zero alphabetic content — caused by 2-token LLM output after
     *    compaction timeout produces stale/thin context (Bug 1 fix).
     * 4. Compaction context echo — LLM pastes "## Retrieved Context ..." header
     *    verbatim into its FINISH answer. Strip the header, keep the content
     *    (Bug 2 fix).
     */
    private String guardFinalAnswer(String answer, int outputTokens, String sessionId) {
        if (answer == null || answer.isBlank()) {
            log.warnf("ANSWER-GUARD: null/blank answer for session %s — returning retry prompt",
                    sessionId);
            return "⚠️ I wasn't able to generate a response. Please try again.";
        }

        String stripped = answer.strip();

        // garbage punctuation-only response from thin-context LLM output
        // e.g. "'." (2 tokens) produced when compaction race starves the LLM of context.
        if (stripped.length() < MIN_ANSWER_ALPHA_LENGTH
                && !stripped.chars().anyMatch(Character::isLetterOrDigit)) {
            log.warnf("ANSWER-GUARD: punctuation-only %d-char answer '%s' suppressed " +
                    "for session %s — likely caused by compaction race producing thin context.",
                    stripped.length(), stripped, sessionId);
            return "⚠️ Context was lost mid-response — please resend your request.";
        }

        if (SUSPICIOUSLY_SHORT.matcher(stripped).matches()) {
            log.warnf("ANSWER-GUARD suppressed suspicious %d-token response: '%s' " +
                    "for session %s — likely caused by compaction race or stale context.",
                    outputTokens, stripped, sessionId);
            return "⚠️ Context was lost mid-response — please resend your request.";
        }

        // The compaction summary is injected into context with this header; the LLM
        // occasionally treats it as its own output and includes it in the FINISH answer.
        if (AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN.matcher(stripped).find()) {
            String withoutHeader = AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN.matcher(stripped).replaceFirst("").strip();
            log.warnf("ANSWER-GUARD: stripped compaction context echo header from answer " +
                    "for session %s (remaining length=%d)", sessionId, withoutHeader.length());
            if (withoutHeader.isBlank()) {
                return "⚠️ I wasn't able to generate a new response. Please try again.";
            }
            return withoutHeader;
        }

        return answer;
    }

    private static final Pattern INTERNAL_JVM_ERROR_PATTERN = Pattern.compile(
        // Matches exception name in thrown form only: "ExceptionType: message"
        // Handles both:
        //   java.lang.NullPointerException: Cannot invoke ...   (fully qualified)
        //   NullPointerException: Cannot invoke ...             (short form)
        // Does NOT match:
        //   "the NullPointerException occurs because..."        (analysis prose)
        //   "analyzing a NullPointerException in OrderService"  (subject usage)
        "(^|[\\n\\r])[ \\t]*((?:java\\.lang\\.)?(?:" +
        "NullPointerException|IllegalArgumentException|" +
        "IllegalStateException|StackOverflowError|OutOfMemoryError" +
        "))[ \\t]*:",
        Pattern.MULTILINE
    );


    /**
     * Ensures raw LangChain4J / JVM internal exception messages never reach the user.
     */
   private String sanitizeErrorForUser(String answer, String sessionId) {
        if (answer == null) return null;
        // Inner sessions (:delegate: prefix) produce developer analysis — never sanitize.
        // A debugger analyzing a NullPointerException will contain "NullPointerException"
        // in its answer — that is expected and must reach the coordinator unmodified.
        if (sessionId != null && sessionId.contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) return answer;
        // For outer sessions: only fire on raw JVM exception form ("ExceptionType: message"),
        // NOT on prose discussion ("the NullPointerException occurs because...").
        if (INTERNAL_JVM_ERROR_PATTERN.matcher(answer).find()) {
            log.warnf("ANSWER-GUARD: sanitized internal error message '%s' for session %s",
                    answer, sessionId);
            return "⚠️ An internal error occurred. Please try again.";
        }
        return answer;
    }

    private static final Pattern PROSE_CODE_PATTERN = Pattern.compile(
            "(?si)here(?:[''']?s| is) a[n]? .{0,60}(?:program|function|class|method|algorithm|implementation|snippet|code|script).*```.*"
    );

    private boolean isGeneratedCodeBlock(String text, String agentRole) {
        if (text == null) return false;
        String stripped = text.stripLeading();

        // For non-coder roles, if they just output a ``` block (e.g. for logs or quoting),
        // we shouldn't falsely block them. But if they actively announce code generation
        // using the prose pattern, we catch them.
        boolean isCoder = AgentRoleEnum.fromString(agentRole).definition().canGenerateCode();
        
        if (!isCoder) {
            // Non-coder: only block if they explicitly announce code generation prose.
            // If they just use Markdown blocks for structured logs, allow it.
            return PROSE_CODE_PATTERN.matcher(stripped).find();
        } else {
            // Coder: we catch it faster for metrics / labeling, though they won't be blocked anyway.
            if (stripped.startsWith("```")) return true;
            return PROSE_CODE_PATTERN.matcher(stripped).find();
        }
    }

    // ── Shared types ──────────────────────────────────────────────────────────
    enum DecisionType { CONTINUE, TERMINAL, PROCEED }

    record StepDecision(
            DecisionType type,
            AgentResult result,
            ReasoningStep step,
            String transientError,
            int llmSchemaErrorCount,
            int consecutiveFailures
    ) {
        static StepDecision proceed(ReasoningStep step) {
            return new StepDecision(DecisionType.PROCEED, null, step, null, 0, 0);
        }
        static StepDecision terminal(AgentResult result) {
            return new StepDecision(DecisionType.TERMINAL, result, null, null, 0, 0);
        }
        static StepDecision retryEngine(String transientError, int llmSchemaErrorCount) {
            return new StepDecision(DecisionType.CONTINUE, null, null, transientError, llmSchemaErrorCount, 0);
        }
        static StepDecision retryValidation(int consecutiveFailures) {
            return new StepDecision(DecisionType.CONTINUE, null, null, null, 0, consecutiveFailures);
        }
        static StepDecision proceedWithFailures(ReasoningStep step, int consecutiveFailures) {
            return new StepDecision(DecisionType.PROCEED, null, step, null, 0, consecutiveFailures);
        }
    }
}
