package com.arcclaw.core.agent.loop;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import org.jboss.logging.Logger;

/**
 * Handles LLM engine invocation, budget events, and all exception classification
 * (schema errors, rate limits, network faults, failed_generation extraction).
 */
@ApplicationScoped
public class EngineStepHandler {

    private static final Logger log = Logger.getLogger(EngineStepHandler.class);

    @Inject GovernanceLayer governanceLayer;
    @Inject Instance<AgentObserver> observers;

    StepDecision processEngineStep(String sessionId, String goal, ReasoningEngine engine,
            List<ReasoningStep> history, String agentRole, String transientError,
            int llmSchemaErrorCount, int iteration) {
        ReasoningStep step;
        try {
            String currentGoal = transientError != null ? goal + "\n\n" + transientError : goal;
            step = engine.nextStep(sessionId, agentRole, currentGoal,
                    Collections.unmodifiableList(history));

            if (step != null) {
                if (ReasoningStep.OBSERVE.equals(step.action())) {
                    log.warnf("[STATE-MACHINE] session %s: schema parse error — retrying with correction", sessionId);
                    llmSchemaErrorCount++;
                    if (llmSchemaErrorCount >= 2) {
                        log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);
                        return StepDecision.terminal(AgentResult.failed(
                                "The agent could not complete this request due to repeated formatting errors.",
                                AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                    }
                    return StepDecision.retryEngine(step.observation(), llmSchemaErrorCount);
                }

                try {
                    governanceLayer.recordLlmCall(sessionId, step.inputTokens(), step.outputTokens());
                    for (var observer : observers)
                        observer.recordLlmCall(sessionId, step.inputTokens(), step.outputTokens());
                } catch (BudgetWarningException bw) {
                    log.warnf("Budget warning for session %s: %s", sessionId, bw.getMessage());
                    step = step.withObservation("⚠️ BUDGET WARNING: " + bw.getMessage() +
                            " Keep all subsequent responses to 1-2 sentences maximum.");
                    history.add(step);
                } catch (BudgetExceededException be) {
                    log.warnf("Governance aborted loop iteration %d: %s", iteration, be.getMessage());
                    step = step.withObservation("System Intervention: " + be.getMessage() +
                            ". Further actions blocked. Ending loop.");
                    history.add(step);
                    return StepDecision.terminal(AgentResult.failed(
                            AgentErrorMessageUtil.sanitizeErrorMessage(be.getMessage()),
                            AgentResult.FailureCategory.BUDGET_EXCEEDED,
                            List.copyOf(history)));
                }
            }
        } catch (Exception e) {
            return handleEngineException(e, sessionId, llmSchemaErrorCount, iteration, history);
        }

        if (step == null) {
            log.warn("ReasoningEngine returned null step — terminating");
            return StepDecision.terminal(AgentResult.failed(
                    "ReasoningEngine returned null step",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history)));
        }
        return StepDecision.proceed(step);
    }

    private StepDecision handleEngineException(Exception e, String sessionId,
            int llmSchemaErrorCount, int iteration, List<ReasoningStep> history) {

        boolean isSchemaError = e instanceof InvalidRequestException
                || (e instanceof HttpException && ((HttpException) e).statusCode() == 400);

        if (isSchemaError) {
            log.warnf("[STATE-MACHINE] LLM schema error session=%s: %s", sessionId, e.getMessage());
            boolean isToolUseFailed = e.getMessage() != null
                    && e.getMessage().contains("tool_use_failed");

            if (isToolUseFailed) {
                // Extract failed_generation — if it's prose, the LLM already has the answer
                // and tried to embed it in a tool call instead of calling FINISH.
                String rawError = e.getMessage() != null ? e.getMessage() : "";
                String failedGen = extractFailedGeneration(rawError);
                if (failedGen != null && looksLikeNaturalLanguage(failedGen)) {
                    log.infof("[STATE-MACHINE] session %s: tool_use_failed contains prose answer — extracting as FINISH", sessionId);
                    return StepDecision.terminal(AgentResult.finished(failedGen, List.copyOf(history)));
                }

                llmSchemaErrorCount++;
                if (llmSchemaErrorCount >= 2) {
                    log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);
                    return StepDecision.terminal(AgentResult.failed(
                            "The agent could not complete this request due to repeated tool schema errors. " +
                            "Try rephrasing your goal.",
                            AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                }

                String correction;
                if (rawError.contains("expected string, but got null")) {
                    correction = "System: Your previous tool call failed because you included an " +
                            "optional field with a null value. " +
                            "RULE: When you have no value for an optional field (such as 'ref' or 'target'), " +
                            "OMIT THE FIELD COMPLETELY — do not include it as null or as an empty string, " +
                            "simply leave it out of the tool call entirely. " +
                            "Retry now with only the fields that have real values.";
                } else {
                    correction = "System: Your previous tool call was rejected due to a schema validation error: "
                            + rawError
                            + ". Review the tool schema carefully, correct the invalid fields, and retry.";
                }

                log.warnf("[STATE-MACHINE] session %s: tool_use_failed — retrying with correction (attempt %d)",
                        sessionId, llmSchemaErrorCount);
                return StepDecision.retryEngine(correction, llmSchemaErrorCount);
            }

            llmSchemaErrorCount++;
            if (llmSchemaErrorCount >= 2) {
                log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);
                return StepDecision.terminal(AgentResult.failed(
                        "SCHEMA_ERROR", AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history)));
            }
            return StepDecision.retryEngine(
                    "System: Your previous tool call was rejected. Error: " + e.getMessage() +
                    ". Please review the tool schema and correct your arguments.",
                    llmSchemaErrorCount);
        }

        if (e instanceof HttpException && ((HttpException) e).statusCode() == 429) {
            String msg = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            log.warnf("[STATE-MACHINE] Rate limit hit session=%s: %s", sessionId, msg);
            return StepDecision.terminal(AgentResult.failed(
                    msg, AgentResult.FailureCategory.RATE_LIMIT, List.copyOf(history)));
        }

        if (isNetworkError(e)) {
            log.errorf("ReasoningEngine network failure at iteration %d: %s", iteration, e.getMessage());
            log.debugf(e, "Full stacktrace for network failure in session %s", sessionId);
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ Unable to reach the AI service. Please check your internet connection and try again.",
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        log.errorf("ReasoningEngine failed at iteration %d: %s", iteration, e.getMessage());
        log.debugf(e, "Full stacktrace for ReasoningEngine failure in session %s", sessionId);
        return StepDecision.terminal(AgentResult.failed(
                "ReasoningEngine error: " + e.getMessage(),
                AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
    }

    private String extractFailedGeneration(String rawError) {
        if (rawError == null) return null;
        try {
            int idx = rawError.indexOf("\"failed_generation\"");
            if (idx < 0) return null;
            int start = rawError.indexOf('"', idx + "\"failed_generation\"".length() + 1);
            if (start < 0) return null;
            StringBuilder sb = new StringBuilder();
            boolean escape = false;
            for (int i = start + 1; i < rawError.length(); i++) {
                char c = rawError.charAt(i);
                if (escape) {
                    if (c == 'n') sb.append('\n');
                    else if (c == 't') sb.append('\t');
                    else sb.append(c);
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            String result = sb.toString().strip();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            log.debugf("extractFailedGeneration parse failed: %s", e.getMessage());
            return null;
        }
    }

    private boolean looksLikeNaturalLanguage(String text) {
        if (text == null || text.isBlank()) return false;
        char first = text.stripLeading().charAt(0);
        if (first == '{' || first == '[') return false;
        return text.contains(" ");
    }

    private boolean isNetworkError(Exception e) {
        if (e instanceof ConnectException || e instanceof SocketTimeoutException) return true;
        if (e.getCause() instanceof ConnectException
                || e.getCause() instanceof SocketTimeoutException) return true;
        String msg = e.getMessage();
        return msg != null && (msg.contains("ConnectTimeoutException")
                || msg.contains("connection timed out")
                || msg.contains("Connection reset")
                || msg.contains("Connection refused"));
    }
}
