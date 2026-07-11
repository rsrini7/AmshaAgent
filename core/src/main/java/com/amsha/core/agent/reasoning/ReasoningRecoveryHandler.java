package com.amsha.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.error.LlmToolCallException;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.util.TextPatternUtils;

import org.jboss.logging.Logger;

@ApplicationScoped
class ReasoningRecoveryHandler {

    private static final Logger log = Logger.getLogger(ReasoningRecoveryHandler.class);

    private final RecoveryProcessor recoveryProcessor;
    private final ReasoningValidator validator;
    private final TextPatternUtils textPatternUtils;

    @Inject
    ReasoningRecoveryHandler(
            RecoveryProcessor recoveryProcessor, ReasoningValidator validator, TextPatternUtils textPatternUtils) {
        this.recoveryProcessor = recoveryProcessor;
        this.validator = validator;
        this.textPatternUtils = textPatternUtils;
    }

    ReasoningStep handleLlmToolCallException(
            LlmToolCallException ex,
            List<ReasoningStep> history,
            String sessionId,
            String targetLanguage,
            AgentExecutionContext context,
            String goal,
            boolean isWriteIntent,
            String agentRole) {
        String msg = ex.getMessage();
        if (!recoveryProcessor.isProseGenerationFailure(msg)) throw ex;

        String extractedProse = recoveryProcessor.extractProseFromFailedGeneration(msg);
        int truncationRetryCount = validator.countTruncationAttempts(history);

        ReasoningStep recoveryStep = tryCodeRecovery(extractedProse, truncationRetryCount, targetLanguage, sessionId);
        if (recoveryStep != null) return recoveryStep;

        if (extractedProse != null && recoveryProcessor.looksLikeTruncatedOrPartialCode(extractedProse)) {
            log.warnf("[LangChainReasoningEngine] Truncated/partial code detected for session %s", sessionId);
            context.incrementTruncationRetry();
            return ReasoningStep.pending("Response was truncated", "OBSERVE", Map.of(), 0, 0)
                    .withTruncationRecovery(true)
                    .withObservation(
                            "Your previous response was cut off. provide the code using a FINISH answer first.");
        }

        ReasoningStep proseStep =
                routeExtractedProse(extractedProse, goal, isWriteIntent, agentRole, history, sessionId);
        if (proseStep != null) return proseStep;

        return ReasoningStep.pending("Generated raw text instead of FINISH", "OBSERVE", Map.of(), 0, 0)
                .withObservation("You must respond using FINISH[answer=<content>].");
    }

    private ReasoningStep tryCodeRecovery(
            String extractedProse, int truncationRetryCount, String targetLanguage, String sessionId) {
        if (extractedProse == null
                || (!recoveryProcessor.containsEmbeddedSandboxToolCall(extractedProse) && truncationRetryCount < 2)) {
            return null;
        }

        String partialCode = recoveryProcessor.extractPartialCodeFromTruncatedJson(extractedProse);
        if (partialCode == null && truncationRetryCount >= 2) {
            partialCode = extractedProse.strip();
        }

        if (partialCode == null || partialCode.isBlank()) {
            return null;
        }

        String lang = textPatternUtils.inferLanguage(partialCode);
        boolean looksLikeCode = partialCode.contains("func ")
                || partialCode.contains("package ")
                || partialCode.contains("def ")
                || partialCode.contains("public class ");
        if (targetLanguage != null && !targetLanguage.equalsIgnoreCase(lang) && !(lang == null && looksLikeCode)) {
            return null;
        }

        log.infof("[LangChainReasoningEngine] Recovered partial code from truncation for session %s", sessionId);
        RecoveryProcessor.CodeBlock extracted = recoveryProcessor.extractCodeBlockFromText(partialCode);
        if (extracted != null && extracted.code() != null) {
            partialCode = extracted.code();
            lang = extracted.language();
        }
        String markdown = String.format("```%s\n%s\n```", lang != null ? lang : "", partialCode);
        return ReasoningStep.pending(
                "Extracted code after repeated truncation attempts.",
                ReasoningStep.FINISH,
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, markdown),
                0,
                0);
    }

    private ReasoningStep routeExtractedProse(
            String extractedProse,
            String goal,
            boolean isWriteIntent,
            String agentRole,
            List<ReasoningStep> history,
            String sessionId) {
        if (extractedProse == null || extractedProse.isBlank()) {
            return null;
        }

        if (recoveryProcessor.containsEmbeddedSandboxToolCall(extractedProse)) {
            return ReasoningStep.pending("Embedded sandbox_exec call", "OBSERVE", Map.of(), 0, 0)
                    .withObservation("CORRECTION: Call sandbox_exec DIRECTLY as your only output.");
        }

        if (textPatternUtils.goalRequestsExecution(goal)) {
            String errorObs = history.stream()
                    .filter(s -> Boolean.FALSE.equals(s.success()) && s.observation() != null)
                    .map(ReasoningStep::observation)
                    .reduce((first, second) -> second)
                    .orElse(null);
            String hint = "STOP. EXECUTE the code - not explain it. Call sandbox_exec RIGHT NOW.";
            if (errorObs != null) {
                hint = "Previous execution failed:\n" + errorObs + "\n\n" + hint;
            }
            return ReasoningStep.pending("LLM returned prose instead of executing", "OBSERVE", Map.of(), 0, 0)
                    .withObservation(hint);
        }

        if (isWriteIntent && !validator.historyAlreadyContains(history, ToolCapability.INGEST_DOCUMENT.toolName())) {
            return buildWriteFallbackStep(goal, sessionId, "model did not emit a tool call");
        }

        if (AgentRoleEnum.COORDINATOR.id().equals(agentRole)) {
            return ReasoningStep.pending(
                    "LLM generated valid prose",
                    "FINISH",
                    Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, extractedProse),
                    0,
                    0);
        }

        if (AgentRoleEnum.CODER.id().equals(agentRole)
                && textPatternUtils.goalRequestsExecution(goal)
                && recoveryProcessor.proseIsUnexecutedCodePlan(extractedProse)) {
            return ReasoningStep.systemMessage("STOP. You must EXECUTE code. Call sandbox_exec RIGHT NOW.");
        }

        return null;
    }

    ReasoningStep buildWriteFallbackStep(String goal, String sessionId, String reason) {
        String text = extractWritePayload(goal);
        log.infof("[WRITE-FALLBACK] Synthesizing ingest_document for session %s: %s", sessionId, reason);
        return ReasoningStep.pending(
                "Deterministic write fallback: " + reason,
                ToolCapability.INGEST_DOCUMENT.toolName(),
                Map.of("text", text, "label", "user_memory"),
                0,
                0);
    }

    private static String extractWritePayload(String goal) {
        if (goal == null || goal.isBlank()) {
            return "User requested that this information be remembered.";
        }
        String text = goal.replaceFirst("(?i)^\\s*(remember|store|save|ingest)(?:\\s+this)?\\s*[:,-]?\\s*", "")
                .trim();
        if (text.isBlank()) {
            text = goal.trim();
        }
        return text.length() > 12000 ? text.substring(0, 12000) : text;
    }
}
