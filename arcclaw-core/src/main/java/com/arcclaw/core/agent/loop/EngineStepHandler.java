package com.arcclaw.core.agent.loop;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentStateMachineRunner.StepDecision;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles LLM engine invocation, budget events, and all exception classification
 * (schema errors, rate limits, network faults, failed_generation extraction).
 */
@ApplicationScoped
public class EngineStepHandler {

    private static final Logger log = Logger.getLogger(EngineStepHandler.class);

    @Inject GovernanceStrategy governanceLayer;
    @Inject Instance<AgentObserver> observers;
    @Inject ObjectMapper objectMapper;

    private static final Pattern NATURAL_LANGUAGE_GATE =
        Pattern.compile("^([A-Za-z*#\\[]).*\\s.*[.!?]\\s*$", Pattern.DOTALL);

    StepDecision processEngineStep(AgentExecutionContext context, ReasoningEngine engine,
            List<ReasoningStep> history, String transientError,
            int llmSchemaErrorCount, int iteration) {
        ReasoningStep step;
        try {
            String goal = context.goal();
            String currentGoal = transientError != null ? goal + "\n\n" + transientError : goal;
            step = engine.nextStep(context, context.agentRole(), currentGoal,
                    Collections.unmodifiableList(history));

            if (step != null) {
                if (ReasoningStep.OBSERVE.equals(step.action())) {
                    log.warnf("[STATE-MACHINE] session %s: schema parse error — retrying with correction", context.sessionId());
                    llmSchemaErrorCount++;
                    if (llmSchemaErrorCount >= 4) {
                        log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", context.sessionId());
                        return StepDecision.terminal(AgentResult.failed(
                                "The agent could not complete this request due to repeated formatting errors.",
                                AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                    }
                    return StepDecision.retryEngine(step.observation(), llmSchemaErrorCount);
                }

                try {
                    governanceLayer.recordLlmCall(context.sessionId(), step.inputTokens(), step.outputTokens());
                    for (var observer : observers)
                        observer.recordLlmCall(context.sessionId(), step.inputTokens(), step.outputTokens());
                } catch (BudgetWarningException bw) {
                    log.warnf("Budget warning for session %s: %s", context.sessionId(), bw.getMessage());
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
            return handleEngineException(e, context.sessionId(), llmSchemaErrorCount, iteration, history);
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

            String rawError = e.getMessage() != null ? e.getMessage() : "";

            // 1. Terminal 400s: Model/Auth/Role configuration errors that retrying won't fix.
            if (rawError.contains("model_not_found") || rawError.contains("does not exist") || rawError.contains("invalid_api_key")) {
                String sanitizedMsg = AgentErrorMessageUtil.sanitizeErrorMessage(rawError);
                log.warnf("[STATE-MACHINE] session %s: terminal configuration error (400): %s",
                        sessionId, sanitizedMsg);
                return StepDecision.terminal(AgentResult.failed(sanitizedMsg,
                        AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
            }

            // 2. Groq-specific: LLM tried a tool not registered in this role's schema.
            // Retrying will never help — terminal immediately with a clear message.
            if (rawError.contains("not in request.tools")) {
                String unavailableTool = extractUnavailableToolName(rawError);
                String msg = unavailableTool != null
                    ? String.format(
                        "I tried to use `%s`, but that tool is not available in this role. "
                        + "Try rephrasing, or start fresh with a more specific request.", unavailableTool)
                    : "I tried to use a tool that is not available in my current role.";
                log.warnf("[STATE-MACHINE] session %s: tool '%s' not in request.tools — terminating immediately",
                        sessionId, unavailableTool);
                return StepDecision.terminal(AgentResult.finished(msg, List.copyOf(history)));
            }

            boolean isToolUseFailed = e.getMessage() != null
                    && e.getMessage().contains("tool_use_failed");

            if (isToolUseFailed) {
                String failedGen = extractFailedGeneration(rawError);
                if (failedGen != null && looksLikeNaturalLanguage(failedGen)) {
                    log.infof("[STATE-MACHINE] session %s: tool_use_failed contains prose — extracting as FINISH", sessionId);
                    return StepDecision.terminal(AgentResult.finished(failedGen, List.copyOf(history)));
                }

                llmSchemaErrorCount++;
                if (llmSchemaErrorCount >= 4) {
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
                            "RULE: Omit optional fields entirely when you have no value for them. " +
                            "Retry now with only the fields that have real values.";

                // ↓ NEW BRANCH — fires when delegate_to_agent is called with wrong shape
                } else if (failedGen != null && failedGen.contains("\"agent\"")) {
                    correction = """
                        System: Your delegate_to_agent call was malformed. STRICT RULES:
                        1. Call delegate_to_agent EXACTLY ONCE per turn — never batch multiple agents.
                        2. Pass exactly TWO flat string parameters only:
                        {"agent": "<name>", "goal": "<self-contained task>"}
                        3. Do NOT wrap in an array []. Do NOT nest under "parameters" or any other key.
                        4. Valid agent names (one at a time): brahma, shiva, saraswati, brihaspati, dattatreya.
                        Retry now with a single flat call — one agent, one goal string.""";

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
            if (llmSchemaErrorCount >= 4) {
                log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);
                return StepDecision.terminal(AgentResult.failed(
                        "The agent encountered repeated tool schema errors and could not complete your request. Please try rephrasing your goal.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history)));
            }
            return StepDecision.retryEngine(
                    "System: Your previous tool call was rejected. Error: " + e.getMessage() +
                    ". Please review the tool schema and correct your arguments.",
                    llmSchemaErrorCount);
        }

        if (e instanceof HttpException && ((HttpException) e).statusCode() == 429) {
            log.warnf("[STATE-MACHINE] Rate limit hit session=%s cause=%s", sessionId, ExceptionUtils.getRootCauseMessage(e));

            int retryAfterSeconds = extractRetryAfter(e.getMessage(), 10);
            if (retryAfterSeconds <= 15) { // only auto-retry for short waits
                log.warnf("[STATE-MACHINE] Rate limit session=%s — sleeping %ds then retrying",
                        sessionId, retryAfterSeconds);
                try { Thread.sleep(retryAfterSeconds * 1000L); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return StepDecision.retryEngine("Rate limit hit, retrying now.", llmSchemaErrorCount);
            }

            // If the last step has a tool observation (e.g. delegate_to_agent result),
            // return it directly as FINISH rather than FAILED — the work is already done.
            if (!history.isEmpty()) {
                String lastObs = history.getLast().observation();
                if (lastObs != null && !lastObs.isBlank()
                        && !lastObs.startsWith("⚠️")
                        && !lastObs.startsWith("Tool execution failed")) {
                    log.infof("[STATE-MACHINE] session %s: rate-limit on wrap-up — surfacing last tool observation as FINISH", sessionId);
                    return StepDecision.terminal(AgentResult.finished(lastObs, List.copyOf(history)));
                }
            }

            // Return AWAITING_RETRY so state machine pauses cleanly
            int retryMs = retryAfterSeconds * 1000;
            return StepDecision.terminal(AgentResult.awaitingRetry(sessionId, retryMs, List.copyOf(history)));
        }

        if (isNetworkError(e)) {
            log.errorf("ReasoningEngine network failure at iteration %d: %s", iteration, ExceptionUtils.getRootCauseMessage(e));
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ Unable to reach the AI service. Please check your internet connection and try again.",
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        log.errorf("ReasoningEngine failed at iteration %d: %s", iteration, ExceptionUtils.getRootCauseMessage(e));
        String sanitizedMsg = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
        return StepDecision.terminal(AgentResult.failed(
                sanitizedMsg,
                AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
    }

    private int extractRetryAfter(String msg, int defaultSeconds) {
        if (msg == null) return defaultSeconds;
        Matcher m = Pattern.compile("try again in ([0-9.]+)s").matcher(msg);
        if (m.find()) return (int) Math.ceil(Double.parseDouble(m.group(1)));
        return defaultSeconds;
    }



    private static final Pattern UNAVAILABLE_TOOL_PATTERN = Pattern.compile("attempted to call tool '([^']+)'");

    private String extractUnavailableToolName(String rawError) {
        // Parses: "attempted to call tool 'manage_schedule' which was not in request.tools"
        Matcher m = UNAVAILABLE_TOOL_PATTERN.matcher(rawError);
        return m.find() ? m.group(1) : null;
    }

    private String extractFailedGeneration(String rawError) {
        if (rawError == null) return null;
        try {
            int start = rawError.indexOf('{');
            if (start < 0) return null;
            JsonNode root =
                objectMapper.readTree(rawError.substring(start));
            JsonNode node =
                root.path("failed_generation");
            if (node.isMissingNode() || !node.isTextual()) return null;
            String result = node.asText().strip();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            log.debugf("extractFailedGeneration parse failed: %s", e.getMessage());
            return null;
        }
    }

    private boolean looksLikeNaturalLanguage(String text) {
        if (text == null || text.length() < 20) return false;
        String trimmed = text.strip();
        char first = trimmed.charAt(0);
        if (first == '{' || first == '<') return false; // allowed '[' for markdown
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("exception") ||
            lower.contains("invalid") || lower.contains("failed") ||
            lower.contains("limit") || lower.contains("permission") ||
            lower.contains("unauthorized") || lower.contains("\"code\":")) return false;
        String[] tokens = trimmed.split("\\s+");
        return tokens.length >= 5 && NATURAL_LANGUAGE_GATE.matcher(trimmed).matches();
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
