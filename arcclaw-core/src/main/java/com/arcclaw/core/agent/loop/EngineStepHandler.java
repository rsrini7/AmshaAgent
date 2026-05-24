package com.arcclaw.core.agent.loop;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import com.arcclaw.core.agent.reasoning.error.LlmAuthenticationException;
import com.arcclaw.core.agent.reasoning.error.LlmContentFilterException;
import com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier;
import com.arcclaw.core.agent.reasoning.error.LlmMalformedResponseException;
import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmToolCallException;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Handles LLM engine invocation, budget events, and all exception classification.
 */
@ApplicationScoped
public class EngineStepHandler {

    private static final Logger log = Logger.getLogger(EngineStepHandler.class);

    private final GovernanceStrategy governanceLayer;
    private final Instance<AgentObserver> observers;
    private final StatefulAgentStore agentStore;
    private final ResponseRecoveryHintBuilder hintBuilder;
    private final LlmExceptionRouter exceptionRouter;
    private final int schemaMaxRetriesStrict;
    private final int schemaMaxRetriesLenient;
    private final int rateLimitMaxAutoRetrySeconds;
    private final int rateLimitDefaultRetrySeconds;

    private static final Set<String> AGENT_IDENTIFIERS = AgentRoleEnum.values().stream()
            .flatMap(r -> Stream.concat(
                    Stream.of(r.id()),
                    r.definition().aliases().stream()))
            .collect(Collectors.toUnmodifiableSet());

    EngineStepHandler() {
        this.governanceLayer = null;
        this.observers = null;
        this.agentStore = null;
        this.hintBuilder = null;
        this.exceptionRouter = null;
        this.schemaMaxRetriesStrict = 0;
        this.schemaMaxRetriesLenient = 0;
        this.rateLimitMaxAutoRetrySeconds = 0;
        this.rateLimitDefaultRetrySeconds = 0;
    }

    @Inject
    public EngineStepHandler(GovernanceStrategy governanceLayer,
                             Instance<AgentObserver> observers,
                             StatefulAgentStore agentStore,
                             ResponseRecoveryHintBuilder hintBuilder,
                             LlmExceptionRouter exceptionRouter,
                             @ConfigProperty(name = "arcclaw.agent.schema-correction.max-retries-strict", defaultValue = "4") int schemaMaxRetriesStrict,
                             @ConfigProperty(name = "arcclaw.agent.schema-correction.max-retries-lenient", defaultValue = "6") int schemaMaxRetriesLenient,
                             @ConfigProperty(name = "arcclaw.agent.rate-limit.max-auto-retry-seconds", defaultValue = "15") int rateLimitMaxAutoRetrySeconds,
                             @ConfigProperty(name = "arcclaw.agent.rate-limit.default-retry-seconds", defaultValue = "10") int rateLimitDefaultRetrySeconds) {
        this.governanceLayer = governanceLayer;
        this.observers = observers;
        this.agentStore = agentStore;
        this.hintBuilder = hintBuilder;
        this.exceptionRouter = exceptionRouter;
        this.schemaMaxRetriesStrict = schemaMaxRetriesStrict;
        this.schemaMaxRetriesLenient = schemaMaxRetriesLenient;
        this.rateLimitMaxAutoRetrySeconds = rateLimitMaxAutoRetrySeconds;
        this.rateLimitDefaultRetrySeconds = rateLimitDefaultRetrySeconds;
    }

    StepDecision processEngineStep(AgentExecutionContext context, ReasoningEngine engine,
            List<ReasoningStep> history, String transientError,
            int llmSchemaErrorCount, int iteration) {
        ReasoningStep step;
        try {
            String currentGoal = AgentSentinels.stripCronPrefix(context.goal());
            
            if (!context.systemNote().isBlank()) {
                currentGoal = currentGoal + "\n\n" + context.systemNote();
            }
            if (transientError != null) {
                currentGoal = currentGoal + "\n\n" + transientError;
            }
            step = engine.nextStep(context, context.agentRole(), currentGoal,
                    Collections.unmodifiableList(history));

            if (step != null) {
                boolean isBlankFinish = step instanceof ReasoningStep.FinishStep finish
                        && (finish.actionInput() == null || finish.actionInput().get("answer") == null 
                            || finish.actionInput().get("answer").toString().isBlank());

                if (!(step instanceof ReasoningStep.ObservationStep) && !step.isFastPath() 
                        && (step.outputTokens() <= 1 || isBlankFinish)) {
                    log.warnf("[STATE-MACHINE] session %s: detected degraded %d-token response - injecting hint", context.sessionId(), step.outputTokens());
                    context.incrementTruncationRetry();
                    return StepDecision.retryEngine(hintBuilder.buildDegradedResponseHint(), llmSchemaErrorCount);
                }

                if (step instanceof ReasoningStep.FinishStep finish) {
                    String answer = finish.actionInput() != null ? String.valueOf(finish.actionInput().get("answer")) : "";
                    if (RawJsonGuard.looksLikeRawToolJson(answer)) {
                        return StepDecision.retryEngine(hintBuilder.buildRawJsonInAnswerHint(), llmSchemaErrorCount);
                    }
                }

                if (step instanceof ReasoningStep.ObservationStep observation) {
                    if (Boolean.TRUE.equals(observation.success())) {
                        return StepDecision.retryEngine(observation.observation(), llmSchemaErrorCount);
                    }
                    
                    if (!observation.isTruncationRecovery()) llmSchemaErrorCount++;
                    int schemaRetryBudget = resolveSchemaRetryBudget(context.structuredOutputMode());

                    if (llmSchemaErrorCount >= schemaRetryBudget) {
                        return StepDecision.terminal(AgentResult.failed(
                                "The agent could not complete this request due to repeated formatting errors.", 
                                AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
                    }
                    return StepDecision.retryEngine(observation.observation(), llmSchemaErrorCount);
                }

                try {
                    governanceLayer.recordLlmCall(context.sessionId(), step.inputTokens(), step.outputTokens());
                    for (var observer : observers) observer.recordLlmCall(context.sessionId(), step.inputTokens(), step.outputTokens());   
                } catch (BudgetWarningException bw) {
                    step = step.withObservation("⚠️ BUDGET WARNING: " + bw.getMessage() + " Keep all subsequent responses to 1-2 sentences maximum.");
                    history.add(step);
                } catch (BudgetExceededException be) {
                    step = step.withObservation("System Intervention: " + be.getMessage() + ". Further actions blocked. Ending loop.");
                    history.add(step);
                    return StepDecision.terminal(AgentResult.failed(AgentErrorMessageUtil.sanitizeErrorMessage(be.getMessage()), AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(history)));
                }
            }
        } catch (Exception e) {
            return handleEngineException(e, context, llmSchemaErrorCount, iteration, history, transientError);      
        }

        if (step == null) {
            return StepDecision.terminal(AgentResult.failed("ReasoningEngine returned null step", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }
        return StepDecision.proceed(step);
    }

    private int resolveSchemaRetryBudget(StructuredOutputMode mode) {
        return switch (mode) {
            case LENIENT -> schemaMaxRetriesLenient;
            case NATIVE, PROMPT_DIRECTIVE -> schemaMaxRetriesStrict;
        };
    }

    private StepDecision handleEngineException(Exception e, AgentExecutionContext context,
            int llmSchemaErrorCount, int iteration, List<ReasoningStep> history, String transientError) {

        LlmProviderException classified = LlmExceptionClassifier.classify(e);

        if (classified instanceof LlmMalformedResponseException) {
            llmSchemaErrorCount++;
            int schemaRetryBudget = resolveSchemaRetryBudget(context.structuredOutputMode());
            if (llmSchemaErrorCount >= schemaRetryBudget) {
                return StepDecision.terminal(AgentResult.failed("The agent encountered repeated tool schema errors.", AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));
            }
            return StepDecision.retryEngine(hintBuilder.buildSchemaCorrectionHint(classified.getMessage()), llmSchemaErrorCount);
        }

        if (classified instanceof LlmAuthenticationException) {
            return StepDecision.terminal(AgentResult.failed(AgentErrorMessageUtil.sanitizeErrorMessage(classified.getMessage()), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
        }

        if (classified instanceof LlmToolCallException) {
            String rawError = classified.getMessage() != null ? classified.getMessage() : "";
            if (rawError.contains("not in request.tools")) {
                String unavailableTool = exceptionRouter.extractUnavailableToolName(rawError);
                if (unavailableTool != null && AGENT_IDENTIFIERS.contains(unavailableTool.toLowerCase())) {     
                    return StepDecision.retryEngine(hintBuilder.buildHallucinatedAgentToolHint(unavailableTool), llmSchemaErrorCount);
                }
                if ("FINISH".equals(unavailableTool)) {
                    String answer = exceptionRouter.extractFinishAnswer(exceptionRouter.extractFailedGeneration(rawError));
                    if (answer != null && !answer.isBlank()) return StepDecision.terminal(AgentResult.finished(answer, List.copyOf(history)));       
                }
                // Tool not available in current role — tell LLM to use garuda to delegate
                String delegateTarget = suggestDelegationRole(unavailableTool);
                String hint = "The tool '" + unavailableTool + "' is not available in your role. "
                    + "Use `garuda(agent=" + delegateTarget + ")` to delegate this task "
                    + "to the appropriate specialist. Do NOT call tools outside your PERMITTED TOOLS list.";
                return StepDecision.retryEngine(hint, llmSchemaErrorCount);
            }

            if (rawError.contains("tool_use_failed")) {
                String failedGen = exceptionRouter.extractFailedGeneration(rawError);
                if (failedGen != null && RawJsonGuard.looksLikeNaturalLanguage(failedGen)) return StepDecision.terminal(AgentResult.finished(failedGen, List.copyOf(history)));        

                llmSchemaErrorCount++;
                int schemaRetryBudget = resolveSchemaRetryBudget(context.structuredOutputMode());
                String toolName = exceptionRouter.extractToolNameFromFailedGen(failedGen);
                
                if (llmSchemaErrorCount >= schemaRetryBudget) return StepDecision.terminal(AgentResult.failed("Repeated tool schema errors.", AgentResult.FailureCategory.SCHEMA_ERROR, List.copyOf(history)));

                String correction = rawError.contains("expected string, but got null")
                        ? hintBuilder.buildNullValueCorrectionHint(toolName != null ? "to '" + toolName + "' " : "")
                        : (failedGen != null && failedGen.contains("\"agent\"") ? hintBuilder.buildMalformedDelegateHint() : hintBuilder.getFallbackDirective());

                return StepDecision.retryEngine(correction, llmSchemaErrorCount);
            }
        }

        if (classified instanceof LlmRateLimitException) {
            int retryAfter = exceptionRouter.extractRetryAfter(classified.getMessage(), rateLimitDefaultRetrySeconds);
            if (retryAfter <= rateLimitMaxAutoRetrySeconds) {
                // Non-blocking wait if possible, but since we return synchronously we await.
                // Using Uni delay satisfies the requirement to move away from direct Thread.sleep.
                Uni.createFrom().nullItem()
                        .onItem().delayIt().by(Duration.ofSeconds(retryAfter))
                        .await().indefinitely();
                return StepDecision.retryRateLimit("Rate limit hit, retrying.", llmSchemaErrorCount);
            }
            return StepDecision.terminal(AgentResult.awaitingRetry(context.sessionId(), retryAfter * 1000, List.copyOf(history)));  
        }

        if (classified instanceof LlmContentFilterException) return StepDecision.terminal(AgentResult.failed(classified.getMessage(), AgentResult.FailureCategory.SECURITY_POLICY, List.copyOf(history)));
        if (isNetworkError(e)) return StepDecision.terminal(AgentResult.failed("⚠️ Unable to reach the AI service.", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));

        return StepDecision.terminal(AgentResult.failed(AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage()), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
    }

    private boolean isNetworkError(Exception e) {
        if (e instanceof ConnectException || e instanceof SocketTimeoutException) return true;
        if (e.getCause() instanceof ConnectException || e.getCause() instanceof SocketTimeoutException) return true;
        String msg = e.getMessage();
        return msg != null && (msg.contains("ConnectTimeoutException") || msg.contains("connection timed out") || msg.contains("Connection reset") || msg.contains("Connection refused"));
    }

    Optional<CheckpointStatus> loadCheckpointStatus(AgentExecutionContext ctx) {
        if (!ctx.isStatusDirty() && ctx.lastCheckpointStatus() != null) return Optional.of(ctx.lastCheckpointStatus());
        return agentStore.load(ctx.sessionId(), ctx.agentRole()).map(cp -> {
            ctx.setLastCheckpointStatus(cp.getStatus());
            return cp.getStatus();
        });
    }

    /**
     * Maps a tool name to the correct agent role for delegation via garuda.
     * When the LLM tries to call a tool not in its current role, this suggests
     * which role to delegate to.
     */
    private static String suggestDelegationRole(String toolName) {
        if (toolName == null) return AgentRoleEnum.COORDINATOR.id();
        if (toolName.equals(ToolCapability.SANDBOX_EXEC.toolName())) return AgentRoleEnum.CODER.id();
        if (toolName.equals(ToolCapability.BROWSER_NAVIGATE.toolName())
                || toolName.equals(ToolCapability.WEB_SEARCH_BRAVE.toolName())
                || toolName.equals(ToolCapability.WEB_SEARCH_SERPER.toolName())
                || toolName.equals(ToolCapability.WEB_FETCH.toolName())
                || toolName.equals(ToolCapability.GITHUB.toolName())) {
            return AgentRoleEnum.RESEARCHER.id();
        }
        return AgentRoleEnum.COORDINATOR.id();
    }
}
