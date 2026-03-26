package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.persona.Agent;
import com.arcclaw.core.agent.persona.AgentRole;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.security.InputSanitizer;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.structured.StructuredOutputException;
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

    // ── Matches completion non-answers: "Done.", "✅ Done.", "OK.", "ok" etc.
    // NOTE: token-count gate removed — step.outputTokens() is 0 for ReasoningStep.pending()
    // paths, making it unreliable. The pattern itself is the real gate.
    private static final Pattern SUSPICIOUSLY_SHORT =
            Pattern.compile(
                "^[✅⏳\\s]*(done\\.?|ok\\.?)\\s*$",
                Pattern.CASE_INSENSITIVE
            );

    // ── LangChain4J / internal error strings that must never reach the user.
    private static final List<String> INTERNAL_ERROR_FRAGMENTS = List.of(
            "text cannot be null or blank",
            "NullPointerException",
            "IllegalArgumentException",
            "IllegalStateException",
            "StackOverflowError",
            "OutOfMemoryError"
    );

    @Inject AgentStepProcessor stepProcessor;
    @Inject ChatMemoryWriter writer;
    @Inject Instance<AgentObserver> observers;
    @Inject @AgentRole("synthesizer") Agent synthesizerAgent;
    @Inject SkillRegistry skillRegistry;
    @Inject @InputSanitizer.PII InputSanitizer piiScanReflex;
    @Inject AgentOtelContext agentOtelContext;

    public AgentResult run(AgentExecutionContext context, ReasoningEngine engine,
                           List<ReasoningStep> resumedHistory) {
        List<ReasoningStep> history = new ArrayList<>(resumedHistory);
        int iteration = 0;
        int llmSchemaErrorCount = 0;
        int consecutiveFailures = 0;
        String transientError = null;

        log.infof("StateMachine starting session=%s goal=%s maxIterations=%d",
                context.sessionId(), piiScanReflex.sanitize(context.goal()), context.maxIterations());

        writer.saveGoal(context.sessionId(), context.goal());

        while (iteration < context.maxIterations()) {
            iteration++;
            Span iterSpan = agentOtelContext.startIterationSpan(
                    context.sessionId(), iteration, context.agentRole());
            try (Scope scope = iterSpan.makeCurrent()) {
                log.debugf("StateMachine iteration %d/%d", iteration, context.maxIterations());

                List<CompiledSkillEntry> skillSnapshot = skillRegistry.currentSnapshot();

                StepDecision engineDecision;
                try {
                    engineDecision = stepProcessor.processEngineStep(
                            context, engine, history, transientError, llmSchemaErrorCount, iteration);
                } catch (StructuredOutputException soe) {
                    log.warnf(
                        "StateMachine schema self-correction exhausted at iteration %d/%d for session %s — " +
                        "treating as loop exceeded to preserve LOOP_MAX_ITERATIONS audit semantics.",
                        iteration, context.maxIterations(), context.sessionId()
                    );
                    return AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
                }

                if (engineDecision.type() == DecisionType.TERMINAL) return engineDecision.result();
                if (engineDecision.type() == DecisionType.CONTINUE) {
                    transientError = engineDecision.transientError();
                    llmSchemaErrorCount = engineDecision.llmSchemaErrorCount();
                    continue;
                }

                ReasoningStep step = engineDecision.step();
                transientError = null;

                if (ReasoningStep.FINISH.equals(step.action()))
                    return handleFinishAction(context.sessionId(), context.agentRole(),
                            history, step, iteration);

                StepDecision validationDecision = stepProcessor.validateTool(
                        context, history, step, consecutiveFailures);
                if (validationDecision.type() == DecisionType.TERMINAL) return validationDecision.result();
                if (validationDecision.type() == DecisionType.CONTINUE) {
                    consecutiveFailures = validationDecision.consecutiveFailures();
                    continue;
                }
                step = validationDecision.step();

                StepDecision invocationDecision = stepProcessor.invokeToolAndObserve(
                        context, history, step, consecutiveFailures, skillSnapshot);
                if (invocationDecision.type() == DecisionType.TERMINAL) return invocationDecision.result();
                consecutiveFailures = invocationDecision.consecutiveFailures();

            } catch (Exception e) {
                iterSpan.recordException(e);
                iterSpan.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                iterSpan.end();
            }
        }

        log.warnf("StateMachine exceeded %d iterations for session=%s",
                context.maxIterations(), context.sessionId());
        return AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
    }

    private AgentResult handleFinishAction(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step, int iteration) {
        log.infof("Agent reached FINISH at iteration %d", iteration);
        String answer = step.thought();
        if (step.actionInput() != null && step.actionInput().containsKey("answer")) {
            answer = String.valueOf(step.actionInput().get("answer"));
        }

        if (answer != null) {
            answer = StringEscapeUtils.unescapeHtml4(answer);
            // unescapeHtml4 doesn't cover JSON unicode escapes — fix explicitly.
            answer = answer.replace("\\u003c", "<")
                          .replace("\\u003e", ">")
                          .replace("\\u0026", "&")
                          .replace("\\u0027", "'");
        }

        // ── Sanitize raw internal exception messages before they reach the user.
        // e.g. "text cannot be null or blank" from LangChain4J must never be shown.
        answer = sanitizeErrorForUser(answer, sessionId);

        if (answer != null && isGeneratedCodeBlock(answer)) {
            if (!AgentRoleEnum.fromString(agentRole).definition().canGenerateCode()) {
                String refusal = "I'm " + agentRole + " — I don't generate code. " +
                        "Please ask Brahma (coder) for code generation tasks.";
                writer.saveAnswer(sessionId, refusal);
                history.add(step.withObservation("Blocked: code generation by non-coder role"));
                return AgentResult.finished(refusal, List.copyOf(history));
            }
        }

        // ── Answer guard: suppress suspiciously short non-answers ("Done.", "OK.")
        // produced when the LLM sees thin compacted context after a compaction race.
        // Token-count gate removed — step.outputTokens() is unreliable (always 0 for
        // ReasoningStep.pending() paths). Pattern-only check is the real gate.
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
                            && !"Task completed.".equals(s.observation()))
                    .map(s -> "Thought: " + s.thought() + "\nFinding: " + s.observation())
                    .collect(Collectors.joining("\n\n"));

            if (combinedContext.isBlank()) {
                return AgentResult.finished(answer, List.copyOf(history));
            }

            return synthesizerAgent.synthesize(sessionId, combinedContext, history);
        }

        return AgentResult.finished(answer, List.copyOf(history));
    }

    /**
     * Suppresses suspiciously short non-answers ("Done.", "OK.") caused by
     * the LLM receiving thin compacted context after a compaction race.
     * Token-count gate intentionally removed — step.outputTokens() is 0 for
     * all ReasoningStep.pending() construction paths, making it unreliable.
     */
    private String guardFinalAnswer(String answer, int outputTokens, String sessionId) {
        if (answer == null || answer.isBlank()) {
            log.warnf("ANSWER-GUARD: null/blank answer for session %s — returning retry prompt",
                    sessionId);
            return "⚠️ I wasn't able to generate a response. Please try again.";
        }
        if (SUSPICIOUSLY_SHORT.matcher(answer.strip()).matches()) {
            log.warnf("ANSWER-GUARD suppressed suspicious %d-token response: '%s' " +
                    "for session %s — likely caused by compaction race or stale context.",
                    outputTokens, answer.strip(), sessionId);
            return "⚠️ Context was lost mid-response — please resend your request.";
        }
        return answer;
    }

    /**
     * Ensures raw LangChain4J / JVM internal exception messages never reach the user.
     * These appear when a null/blank tool result leaks into chat history and the next
     * LLM call fails with "text cannot be null or blank" from LangChain4J validation.
     */
    private String sanitizeErrorForUser(String answer, String sessionId) {
        if (answer == null) return null;
        for (String fragment : INTERNAL_ERROR_FRAGMENTS) {
            if (answer.contains(fragment)) {
                log.warnf("ANSWER-GUARD: sanitized internal error message '%s' for session %s",
                        answer, sessionId);
                return "⚠️ An internal error occurred. Please try again.";
            }
        }
        return answer;
    }

    private boolean isGeneratedCodeBlock(String text) {
        if (text == null) return false;
        String stripped = text.stripLeading();
        if (stripped.startsWith("```")) return true;
        if (stripped.matches("(?si)here[''']?s a .{0,60}(program|function|class|"
                + "method|algorithm|implementation|snippet|code).*```.*")) return true;
        if (stripped.matches("(?si)here is a .{0,60}(program|function|class|"
                + "method|algorithm|implementation|snippet|code).*```.*")) return true;
        return false;
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