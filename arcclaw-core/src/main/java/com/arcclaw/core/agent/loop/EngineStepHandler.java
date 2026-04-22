package com.arcclaw.core.agent.loop;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.error.LlmAuthenticationException;
import com.arcclaw.core.agent.reasoning.error.LlmContentFilterException;
import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import com.arcclaw.core.agent.reasoning.error.LlmMalformedResponseException;
import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmToolCallException;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles LLM engine invocation, budget events, and all exception classification
 * (schema errors, rate limits, network faults, failed_generation extraction).
 * Refactored to constructor injection as part of T4-1.
 */
@ApplicationScoped
public class EngineStepHandler {

    private static final Logger log = Logger.getLogger(EngineStepHandler.class);

    private final GovernanceStrategy governanceLayer;
    private final Instance<AgentObserver> observers;
    private final ObjectMapper objectMapper;
    private final RateLimitTracker rateLimitTracker;
    private final StatefulAgentStore agentStore;

    @Inject
    @Identifier("agent-output-mode")
    String structuredOutputModeStr;

    private static final Pattern NATURAL_LANGUAGE_GATE =
        Pattern.compile("^([A-Za-z*#\\[]).*\\s.*[.!?]\\s*$", Pattern.DOTALL);

    private static final Set<String> AGENT_IDENTIFIERS = AgentRoleEnum.values().stream()
            .flatMap(r -> Stream.concat(
                    Stream.of(r.id()),
                    r.definition().aliases().stream()))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * CDI proxyability constructor — required by @ApplicationScoped for
     * subclass proxy generation. NEVER called at runtime; all real
     * instances are created via the @Inject constructor below.
     *
     * <p>GraalVM native image may invoke this reflectively at build time.
     * Do NOT throw here. Fields are intentionally null — any runtime call
     * to a method on a proxy instance backed by this constructor will NPE,
     * which is the correct failure signal for misconfigured injection.
     */
    EngineStepHandler() {
        this.governanceLayer = null;
        this.observers = null;
        this.objectMapper = null;
        this.rateLimitTracker = null;
        this.agentStore = null;
    }

    @Inject
    public EngineStepHandler(GovernanceStrategy governanceLayer,
                             Instance<AgentObserver> observers,
                             ObjectMapper objectMapper,
                             RateLimitTracker rateLimitTracker,
                             StatefulAgentStore agentStore) {
        this.governanceLayer = governanceLayer;
        this.observers = observers;
        this.objectMapper = objectMapper;
        this.rateLimitTracker = rateLimitTracker;
        this.agentStore = agentStore;
    }

    StepDecision processEngineStep(AgentExecutionContext context, ReasoningEngine engine,
            List<ReasoningStep> history, String transientError,
            int llmSchemaErrorCount, int iteration) {
        ReasoningStep step;
        try {
            String goal = context.goal();
            // §7.45: Strip 'CRON ' prefix so models don't confuse background execution
            // with a new scheduling request (causes task refusal in weak models).
            String currentGoal = AgentSentinels.stripCronPrefix(goal);
            
            if (!context.systemNote().isBlank()) {
                currentGoal = currentGoal + "\n\n" + context.systemNote();
            }
            if (transientError != null) {
                currentGoal = currentGoal + "\n\n" + transientError;
            }
            step = engine.nextStep(context, context.agentRole(), currentGoal,
                    Collections.unmodifiableList(history));

            if (step != null) {
                // If the model degraded (often after a rate-limit sleep) and returned
                // 1 token or blank, inject a recovery hint rather than passing it through
                // to the quality guard fallback.
                boolean isBlankFinish = ReasoningStep.FINISH.equals(step.action()) 
                        && (step.actionInput() == null || step.actionInput().get("answer") == null 
                            || step.actionInput().get("answer").toString().isBlank());

                // 🟢 FIX: Exempt OBSERVE from degraded check. OBSERVE is a logical correction 
                // step generated by the engine (often with 0 tokens) and is not a sign of model collapse.
                if (!ReasoningStep.OBSERVE.equals(step.action()) && (step.outputTokens() <= 1 || isBlankFinish)) {
                    log.warnf("[STATE-MACHINE] session %s: detected degraded %d-token response (action=%s) — injecting recovery hint",
                            context.sessionId(), step.outputTokens(), step.action());
                    return StepDecision.retryEngine(
                        "System: Your last response was empty, contained only one token, or lacked a FINISH answer. " +
                        "This often happens due to infrastructure limits or model collapse after a rate-limit hit. " +
                        "Please retry now with your full thought process and a complete FINISH answer.",
                        llmSchemaErrorCount);
                    // → re-invokes LLM for a new thought on the next iteration (isRetryValidation=false)
                }

                // 🟢 FIX: Detect if the model output raw JSON that looks like tool arguments instead of a tool call.
                // This happens when a model 'collapses' after a prior tool_use_failed error.
                if (ReasoningStep.FINISH.equals(step.action())) {
                    String answer = step.actionInput() != null ? String.valueOf(step.actionInput().get("answer")) : "";
                    if (looksLikeRawToolJson(answer)) {
                        log.warnf("[STATE-MACHINE] session %s: detected raw JSON in prose answer — injecting recovery hint", context.sessionId());
                        return StepDecision.retryEngine(
                            "System: Your previous response contained raw JSON parameters in the prose answer field. " +
                            "STRICT RULE: You MUST NOT output JSON in the 'answer' field. " +
                            "If you want to perform an action, you MUST use a real structured tool call (e.g. schedule_task). " +
                            "Please retry now using a proper tool call.",
                            llmSchemaErrorCount);
                    }
                }

                if (ReasoningStep.OBSERVE.equals(step.action())) {
                    log.warnf("[STATE-MACHINE] session %s: schema parse error — retrying with correction", context.sessionId());
                    llmSchemaErrorCount++;
                    // TODO (LENIENT mode): When structuredOutputMode == LENIENT, increase
                    // maxRetries for schema correction from 4 to 6+ before hard-failing the turn.
                    // Tracked: EngineStepHandler retry budget should be mode-aware.
                    if (llmSchemaErrorCount >= 4) {
                        log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", context.sessionId());
                        return StepDecision.terminal(AgentResult.failed(
                                "The agent could not complete this request due to repeated formatting errors.", 
                                AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                    }
                    return StepDecision.retryEngine(step.observation(), llmSchemaErrorCount);
                    // → re-invokes LLM for a new thought on the next iteration (isRetryValidation=false)
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

        LlmProviderException classified =
            LlmExceptionClassifier.classify(e);

        if (classified instanceof LlmMalformedResponseException) {
            log.warnf("[STATE-MACHINE] LLM schema error session=%s: %s", sessionId, classified.getMessage());   

            llmSchemaErrorCount++;
            if (llmSchemaErrorCount >= 4) {
                log.errorf("[STATE-MACHINE] session %s exceeded schema self-correction limit", sessionId);      
                return StepDecision.terminal(AgentResult.failed(
                        "The agent encountered repeated tool schema errors and could not complete your request. Please try rephrasing your goal.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history)));
            }
            return StepDecision.retryEngine(
                    "System: Your previous tool call was rejected. Error: " + classified.getMessage() +
                    ". Please review the tool schema and correct your arguments.",
                    llmSchemaErrorCount);
        }

        if (classified instanceof LlmAuthenticationException) {
            String sanitizedMsg = AgentErrorMessageUtil.sanitizeErrorMessage(classified.getMessage());
            log.warnf("[STATE-MACHINE] session %s: terminal configuration error (400): %s",
                    sessionId, sanitizedMsg);
            return StepDecision.terminal(AgentResult.failed(sanitizedMsg,
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        if (classified instanceof LlmToolCallException) {
            String rawError = classified.getMessage() != null ? classified.getMessage() : "";

            if (rawError.contains("not in request.tools")) {
                String unavailableTool = extractUnavailableToolName(rawError);

                // Bug fix: LLM hallucinated agent name as tool — inject correction and retry
                if (unavailableTool != null && AGENT_IDENTIFIERS.contains(unavailableTool.toLowerCase())) {     
                    log.warnf("[STATE-MACHINE] session %s: LLM called agent '%s' as tool — retrying with correction",
                            sessionId, unavailableTool);
                    String correction = String.format(
                        "System: You called '%s' as if it were a tool, but that is an agent name. " +
                        "STRICT RULE: Only use 'delegate_to_agent' for delegation. " +
                        "Example: delegate_to_agent(agent=\"%s\", goal=\"...\"). " +
                        "Retry now with the correct tool call.",
                        unavailableTool, unavailableTool);
                    return StepDecision.retryEngine(correction, llmSchemaErrorCount);
                }

                if ("FINISH".equals(unavailableTool)) {
                    String failedGen = extractFailedGeneration(rawError);
                    String answer = extractFinishAnswer(failedGen);
                    if (answer != null && !answer.isBlank()) {
                        log.infof("[STATE-MACHINE] session %s: recovered FINISH answer despite schema error", sessionId);
                        return StepDecision.terminal(AgentResult.finished(answer, List.copyOf(history)));       
                    }
                }

                String msg = unavailableTool != null
                    ? String.format(
                        "I tried to use `%s`, but that tool is not available in this role. "
                        + "Try rephrasing, or start fresh with a more specific request.", unavailableTool)      
                    : "I tried to use a tool that is not available in my current role.";
                log.warnf("[STATE-MACHINE] session %s: tool '%s' not in request.tools — terminating immediately",
                        sessionId, unavailableTool);
                return StepDecision.terminal(AgentResult.finished(msg, List.copyOf(history)));
            }

            boolean isToolUseFailed = rawError.contains("tool_use_failed");

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

        }

        if (classified instanceof LlmRateLimitException) {
            log.warnf("[STATE-MACHINE] Rate limit hit session=%s cause=%s", sessionId, ExceptionUtils.getRootCauseMessage(e));

            int retryAfterSeconds = extractRetryAfter(classified.getMessage(), 10);
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
                String lastObs = history.get(history.size() - 1).observation();
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

        if (classified instanceof LlmContentFilterException) {
            log.warnf("[STATE-MACHINE] session %s: content filter triggered: %s",
                    sessionId, classified.getMessage());
            return StepDecision.terminal(AgentResult.failed(classified.getMessage(),
                    AgentResult.FailureCategory.SECURITY_POLICY, List.copyOf(history)));
        }

        if (isNetworkError(e)) {
            log.errorf("ReasoningEngine network failure at iteration %d: %s", iteration, ExceptionUtils.getRootCauseMessage(e));
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ Unable to reach the AI service. Please check your internet connection and try again.",  
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        log.errorf("ReasoningEngine failed at iteration %d: %s", iteration, ExceptionUtils.getRootCauseMessage(e));
        String rawError = e.getMessage() != null ? e.getMessage() : "";
        if (AgentErrorMessageUtil.isRateLimitError(rawError)
                || rateLimitTracker.isBackingOff("primary")) {
            return StepDecision.terminal(AgentResult.failed(
                    "⚠️ I hit a rate limit. Please resend your message to retry.",
                    AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }
        String sanitizedMsg = AgentErrorMessageUtil.sanitizeErrorMessage(rawError);
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
            JsonNode node = root.path("failed_generation");
            if (node.isMissingNode()) {
                node = root.path("failedgeneration"); // fallback for some providers
            }
            if (node.isMissingNode() || !node.isTextual()) return null;
            String result = node.asText().strip();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            log.debugf("extractFailedGeneration parse failed: %s", e.getMessage());
            return null;
        }
    }

    private String extractFinishAnswer(String failedGeneration) {
        if (failedGeneration == null || failedGeneration.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(failedGeneration);
            JsonNode answerNode = root.path("answer");
            if (answerNode.isMissingNode()) {
                // Check inside "parameters" (standard tool call schema)
                answerNode = root.path("parameters").path("answer");
            }
            if (answerNode.isTextual()) {
                return answerNode.asText().strip();
            }
        } catch (Exception e) {
            log.debugf("extractFinishAnswer parse failed: %s", e.getMessage());
        }
        return null;
    }

    private boolean looksLikeRawToolJson(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.strip();
        // Must look structurally like JSON first (§7.46)
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false;
        
        String lower = trimmed.toLowerCase(Locale.ROOT);
        // Look for common tool argument keys that should never be in a prose answer
        return lower.contains("\"cronexpression\"") 
            || lower.contains("\"cron_expression\"")
            || (lower.contains("\"goal\"") && lower.contains("\"runonce\""))
            || lower.contains("\"task_payload\"")
            || lower.contains("\"task_name\"")
            || (lower.contains("\"agent\"") && lower.contains("\"goal\""));
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

    /**
     * Hot-path cache: returns cached status if clean, else reloads from store.
     */
    Optional<CheckpointStatus> loadCheckpointStatus(AgentExecutionContext ctx) {
        if (!ctx.isStatusDirty() && ctx.lastCheckpointStatus() != null) {
            return Optional.of(ctx.lastCheckpointStatus());
        }
        return agentStore.load(ctx.sessionId(), ctx.agentRole())
                .map(cp -> {
                    CheckpointStatus s = cp.getStatus();
                    ctx.setLastCheckpointStatus(s);
                    return s;
                });
    }
}
