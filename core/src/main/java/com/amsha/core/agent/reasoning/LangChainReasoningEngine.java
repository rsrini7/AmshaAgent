package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.guardrail.GuardrailCoordinator;
import com.amsha.core.observability.AgentOTelConstants;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmToolCallException;
import com.amsha.tool.api.ToolCapability;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * Orchestrates the reasoning process using LangChain4j.
 * Delegates message assembly, recovery processing, and structured output to specialized components.
 */
@ApplicationScoped
@SuppressWarnings("java:S1192")
public class LangChainReasoningEngine implements ReasoningEngine {

    private static final Logger log = Logger.getLogger(LangChainReasoningEngine.class);

    private final LlmProviderPort llmProviderPort;
    private final RoleRegistry roleRegistry;
    private final SystemPromptBuilder promptBuilder;
    private final RateLimitTracker rateLimitTracker;
    private final IntentClassificationService intentClassifier;
    private final MeterRegistry meterRegistry;
    private final TextPatternUtils textPatternUtils;
    private final ReasoningStepParser stepParser;
    private final ReasoningMessageAssembler messageAssembler;
    private final RecoveryProcessor recoveryProcessor;
    private final ReasoningRecoveryHandler recoveryHandler;
    private final StructuredOutputProcessor structuredOutputProcessor;
    private final ReasoningValidator validator;
    private final GuardrailCoordinator guardrailCoordinator;

    @Inject
    AgentOtelContext otelContext;

    @Inject
    public LangChainReasoningEngine(
            LlmProviderPort llmProviderPort,
            RoleRegistry roleRegistry,
            SystemPromptBuilder promptBuilder,
            RateLimitTracker rateLimitTracker,
            IntentClassificationService intentClassifier,
            MeterRegistry meterRegistry,
            TextPatternUtils textPatternUtils,
            ReasoningStepParser stepParser,
            ReasoningMessageAssembler messageAssembler,
            RecoveryProcessor recoveryProcessor,
            ReasoningRecoveryHandler recoveryHandler,
            StructuredOutputProcessor structuredOutputProcessor,
            ReasoningValidator validator,
            GuardrailCoordinator guardrailCoordinator) {
        this.llmProviderPort = llmProviderPort;
        this.roleRegistry = roleRegistry;
        this.promptBuilder = promptBuilder;
        this.rateLimitTracker = rateLimitTracker;
        this.intentClassifier = intentClassifier;
        this.meterRegistry = meterRegistry;
        this.textPatternUtils = textPatternUtils;
        this.stepParser = stepParser;
        this.messageAssembler = messageAssembler;
        this.recoveryProcessor = recoveryProcessor;
        this.recoveryHandler = recoveryHandler;
        this.structuredOutputProcessor = structuredOutputProcessor;
        this.validator = validator;
        this.guardrailCoordinator = guardrailCoordinator;
    }

    public LangChainReasoningEngine(
            LlmProviderPort llmProviderPort,
            RoleRegistry roleRegistry,
            SystemPromptBuilder promptBuilder,
            RateLimitTracker rateLimitTracker,
            IntentClassificationService intentClassifier,
            MeterRegistry meterRegistry,
            TextPatternUtils textPatternUtils,
            ReasoningStepParser stepParser,
            ReasoningMessageAssembler messageAssembler,
            RecoveryProcessor recoveryProcessor,
            StructuredOutputProcessor structuredOutputProcessor,
            ReasoningValidator validator,
            GuardrailCoordinator guardrailCoordinator) {
        this(
                llmProviderPort,
                roleRegistry,
                promptBuilder,
                rateLimitTracker,
                intentClassifier,
                meterRegistry,
                textPatternUtils,
                stepParser,
                messageAssembler,
                recoveryProcessor,
                new ReasoningRecoveryHandler(recoveryProcessor, validator, textPatternUtils),
                structuredOutputProcessor,
                validator,
                guardrailCoordinator);
    }

    protected LangChainReasoningEngine() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
        return structuredOutputProcessor.chatForStructuredOutput(sessionId, agentRole, schemaPrompt);
    }

    @Override
    public ReasoningStep nextStep(
            AgentExecutionContext context, String agentRole, String goal, List<ReasoningStep> history) {
        String sessionId = context.sessionId();
        int MAX_CARRY_STEPS = 10;
        List<ReasoningStep> cappedHistory = (history != null && history.size() > MAX_CARRY_STEPS)
                ? history.subList(history.size() - MAX_CARRY_STEPS, history.size())
                : history;

        String targetLanguage = textPatternUtils.goalMentionsLanguage(context.goal());

        ReasoningStep fastPathStep = tryFastPath(context, agentRole, history, cappedHistory, targetLanguage, sessionId);
        if (fastPathStep != null) return fastPathStep;

        boolean isTruncationRecovery = context.getTruncationRetryCount() > 0;
        String systemPrompt = promptBuilder.build(context, agentRole, sessionId, goal, isTruncationRecovery);

        // ── Input guardrails (Layer 7) ──
        // The goal is already PII-sanitized by the reflex pipeline (Layer 3).
        // This guard checks injection on the goal and applies extra PII sanitization
        // to the system prompt as a secondary safety net.
        if (guardrailCoordinator != null) {
            String userId = WorkspaceContext.getUserIdOrUnknown();
            GuardrailCoordinator.InputResult result =
                    guardrailCoordinator.runInputGuards(sessionId, userId, goal, systemPrompt);
            if (!result.allowed()) {
                return ReasoningStep.pending(
                                "Blocked by input guard: " + result.blockReason(),
                                ReasoningStep.OBSERVE,
                                Map.of(),
                                0,
                                0)
                        .withObservation(result.blockReason());
            }
            if (result.sanitizedPrompt() != null) {
                systemPrompt = result.sanitizedPrompt();
            }
        }

        String finalGoal = buildFinalGoal(context, agentRole, goal, cappedHistory);

        boolean isWriteIntent = intentClassifier.isWriteIntent(goal);
        boolean isSkillRole = AgentRoleEnum.isSkillRole(agentRole);
        boolean isSimpleChitchat = !isSkillRole && intentClassifier.isSimpleConversational(goal);
        boolean isHistoryQuery = intentClassifier.isHistoryQuery(goal);
        if (isWriteIntent
                || isHistoryQuery
                || AgentRoleEnum.CODER.id().equalsIgnoreCase(agentRole)
                || AgentRoleEnum.RESEARCHER.id().equalsIgnoreCase(agentRole)
                || AgentRoleEnum.DEBUGGER.id().equalsIgnoreCase(agentRole)) {
            isSimpleChitchat = false;
        }

        Set<String> allowedTools = roleRegistry.resolvePermittedTools(agentRole);
        ReasoningMessageAssembler.AssembledMessages assembled = messageAssembler.assemble(
                context,
                agentRole,
                finalGoal,
                cappedHistory,
                systemPrompt,
                allowedTools,
                isWriteIntent,
                isSimpleChitchat,
                isHistoryQuery);

        ChatRequest request = assembled.tools().isEmpty()
                ? ChatRequest.builder().messages(assembled.messages()).build()
                : ChatRequest.builder()
                        .messages(assembled.messages())
                        .toolSpecifications(assembled.tools())
                        .build();

        ChatResponse response;
        try {
            response = executeLlmStep(isSimpleChitchat, context, isWriteIntent, request, agentRole);
        } catch (Exception e) {
            return handleLlmException(e, history, sessionId, targetLanguage, context, goal, isWriteIntent, agentRole);
        }

        // ── Output guardrails (Layer 7) ─────────────────────────────────
        if (guardrailCoordinator != null) {
            String userId = WorkspaceContext.getUserIdOrUnknown();
            String rawText = response.aiMessage() != null ? response.aiMessage().text() : "";
            GuardrailCoordinator.OutputResult outputResult =
                    guardrailCoordinator.runOutputGuards(sessionId, userId, goal, rawText);
            if (!outputResult.allowed()) {
                if (outputResult.retryable() && outputResult.retryResponse() != null) {
                    log.infof("[GUARD] Output guard retry for session %s", sessionId);
                    return ReasoningStep.pending("Output guard triggered retry", ReasoningStep.OBSERVE, Map.of(), 0, 0)
                            .withObservation(outputResult.blockReason());
                }
                return ReasoningStep.pending(
                                "Blocked by output guard: " + outputResult.blockReason(),
                                ReasoningStep.OBSERVE,
                                Map.of(),
                                0,
                                0)
                        .withObservation(outputResult.blockReason());
            }
        }

        return parseAndValidateStep(response, sessionId, agentRole, history, context, isWriteIntent, targetLanguage);
    }

    private ReasoningStep tryFastPath(
            AgentExecutionContext context,
            String agentRole,
            List<ReasoningStep> history,
            List<ReasoningStep> cappedHistory,
            String targetLanguage,
            String sessionId) {
        boolean hasRecentExecution = history != null
                && history.stream()
                        .anyMatch(s -> ToolCapability.SANDBOX_EXEC.toolName().equals(s.action())
                                && Boolean.TRUE.equals(s.success()));

        if (AgentRoleEnum.CODER.id().equals(agentRole)
                && (textPatternUtils.goalRequestsExecution(context.goal())
                        || (hasRecentExecution && targetLanguage != null))
                && !context.isForceFinishRetry()) {

            RecoveryProcessor.CodeBlock priorCode = recoveryProcessor.extractLatestCodeBlock(cappedHistory);
            String langForCheck = (targetLanguage == null && priorCode != null) ? priorCode.language() : targetLanguage;

            if (!validator.historyAlreadyContainsAttemptForLanguage(
                    history, ToolCapability.SANDBOX_EXEC.toolName(), langForCheck, sessionId)) {
                if (priorCode != null
                        && (targetLanguage == null || targetLanguage.equalsIgnoreCase(priorCode.language()))) {
                    log.infof(
                            "[LangChainReasoningEngine] Fast-path: Reusing prior %s code block for session %s",
                            priorCode.language(), sessionId);
                    return ReasoningStep.fastPath(
                            "Fast-path execution from history.",
                            ToolCapability.SANDBOX_EXEC.toolName(),
                            Map.of("code", priorCode.code(), "language", priorCode.language()));
                }
            }
        }
        return null;
    }

    private String buildFinalGoal(
            AgentExecutionContext context, String agentRole, String goal, List<ReasoningStep> cappedHistory) {
        String finalGoal = goal;
        if (AgentRoleEnum.CODER.id().equals(agentRole)
                && textPatternUtils.goalRequestsExecution(context.goal())
                && !context.isForceFinishRetry()) {
            long observeCount = cappedHistory == null
                    ? 0
                    : cappedHistory.stream()
                            .skip(context.getCheckpointRestoreOffset())
                            .filter(s -> ReasoningStep.OBSERVE.equals(s.action()))
                            .count();
            if (observeCount == 0)
                finalGoal = goal + "\n\nSTRICT: You MUST execute code via sandbox_exec. Do NOT just write prose.";
        }
        return finalGoal;
    }

    private ChatResponse executeLlmStep(
            boolean isSimpleChitchat,
            AgentExecutionContext context,
            boolean isWriteIntent,
            ChatRequest request,
            String agentRole) {
        boolean configCompact = promptBuilder.isCompactPromptEnabled();
        boolean isCompact = configCompact || isSimpleChitchat || context.isForceFinishRetry();
        Span span = otelContext.childSpan(context.sessionId(), AgentOTelConstants.SPAN_LLM_PROVIDER_CALL);
        try {
            int msgCount = request.messages() != null ? request.messages().size() : 0;
            int estTokens = ChatRequestMetrics.estimateTokens(request);
            int totalChars = ChatRequestMetrics.countChars(request);
            if (span != null) {
                span.setAttribute(AgentOTelConstants.ATTR_LLM_WRITE_INTENT, isWriteIntent);
                span.setAttribute("amsha.llm.is_compact", isCompact);
                span.setAttribute(AgentOTelConstants.ATTR_LLM_MESSAGE_COUNT, (long) msgCount);
            }
            log.infof(
                    "LLM call: %d messages, ~%d est. tokens, %d chars, role=%s, runtimeCompact=%s (configCompact=%s, chitchat=%s, forceFinishRetry=%s)",
                    msgCount,
                    estTokens,
                    totalChars,
                    context.agentRole(),
                    isCompact,
                    configCompact,
                    isSimpleChitchat,
                    context.isForceFinishRetry());
            // Consume once per call; normal calls keep the legacy 2-arg port shape so existing
            // port implementations and stubs are unaffected. Only an armed memory-synthesis
            // escalation takes the bypass-lightweight-routing overload.
            boolean escalateThisCall = context.consumeReasoningEscalation();
            ChatResponse response = ScopedValue.where(WorkspaceContext.IS_WRITE_INTENT, isWriteIntent)
                    .where(WorkspaceContext.IS_COMPACT, isCompact)
                    .call(() -> escalateThisCall
                            ? llmProviderPort.nextStep(request, agentRole, true)
                            : llmProviderPort.nextStep(request, agentRole));
            if (span != null) {
                if (response.tokenUsage() != null) {
                    span.setAttribute(AgentOTelConstants.ATTR_LLM_INPUT_TOKENS, (long)
                            response.tokenUsage().inputTokenCount());
                    span.setAttribute(AgentOTelConstants.ATTR_LLM_OUTPUT_TOKENS, (long)
                            response.tokenUsage().outputTokenCount());
                }
                span.setStatus(StatusCode.OK);
            }
            return response;
        } catch (Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            throw e;
        } finally {
            if (span != null) span.end();
        }
    }

    private ReasoningStep handleLlmException(
            Exception e,
            List<ReasoningStep> history,
            String sessionId,
            String targetLanguage,
            AgentExecutionContext context,
            String goal,
            boolean isWriteIntent,
            String agentRole) {
        Throwable effective = (e.getClass() == Exception.class && e.getCause() != null) ? e.getCause() : e;
        if (effective instanceof LlmMalformedResponseException ex) throw ex;

        if (effective instanceof LlmToolCallException ex) {
            return recoveryHandler.handleLlmToolCallException(
                    ex, history, sessionId, targetLanguage, context, goal, isWriteIntent, agentRole);
        }

        if (effective instanceof LlmRateLimitException) {
            meterRegistry.counter("amsha.llm.rate_limit.hit").increment();
            rateLimitTracker.recordHit("primary");
            throw (LlmRateLimitException) effective;
        }
        if (effective instanceof RuntimeException re) throw re;
        throw new RuntimeException(effective);
    }

    private ReasoningStep parseAndValidateStep(
            ChatResponse response,
            String sessionId,
            String agentRole,
            List<ReasoningStep> history,
            AgentExecutionContext context,
            boolean isWriteIntent,
            String targetLanguage) {
        ReasoningStep parsedStep = stepParser.parse(sessionId, agentRole, response);

        if (ReasoningStep.FINISH.equals(parsedStep.action())
                && AgentRoleEnum.isSkillRole(agentRole)
                && response.aiMessage() != null
                && !response.aiMessage().hasToolExecutionRequests()
                && !context.isForceFinishRetry()) {
            return ReasoningStep.systemMessage("CRITICAL SKILL MODE: You have not executed any permitted tools yet. "
                    + "Call your execution tool directly and do not answer in prose. "
                    + "After the tool returns, FINISH with a concise summary.");
        }

        if (ReasoningStep.FINISH.equals(parsedStep.action())
                && isWriteIntent
                && !validator.historyAlreadyContains(history, ToolCapability.INGEST_DOCUMENT.toolName())) {
            return recoveryHandler.buildWriteFallbackStep(
                    context.goal(), sessionId, "model returned FINISH instead of a write tool");
        }

        if (ReasoningStep.FINISH.equals(parsedStep.action())
                && AgentRoleEnum.CODER.id().equals(agentRole)
                && textPatternUtils.goalRequestsExecution(context.goal())
                && targetLanguage != null
                && !context.isForceFinishRetry()) {
            if (!validator.historyAlreadyContainsAttemptForLanguage(
                    history, ToolCapability.SANDBOX_EXEC.toolName(), targetLanguage, sessionId)) {
                long obsCorr = history.stream()
                        .skip(context.getCheckpointRestoreOffset())
                        .filter(s -> ReasoningStep.OBSERVE.equals(s.action()))
                        .count();
                if (obsCorr >= 2) return parsedStep;
                return ReasoningStep.systemMessage("STOP. You must EXECUTE code. Call sandbox_exec RIGHT NOW.");
            }
        }
        return parsedStep;
    }
}
