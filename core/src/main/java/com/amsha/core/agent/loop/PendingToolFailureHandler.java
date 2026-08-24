package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.message.UserMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Handles recovery and persona escalation after a resumed tool fails. */
final class PendingToolFailureHandler {
    private static final Logger log = Logger.getLogger(PendingToolFailureHandler.class);

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ReasoningEngine reasoningEngine;
    private final ExecutionFailureRecoveryService recoveryService;
    private final PersonaEscalationService escalationService;
    private final PendingToolResumeExecutor executor;

    PendingToolFailureHandler(
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            ReasoningEngine reasoningEngine,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService,
            PendingToolResumeExecutor executor) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.reasoningEngine = reasoningEngine;
        this.recoveryService = recoveryService;
        this.escalationService = escalationService;
        this.executor = executor;
    }

    AgentResult tryAutoHeal(PendingToolResumeExecutor.PendingToolRequest request, ToolResponse response) {
        if (!(response instanceof ToolResponse.ToolFailure failure)
                || !recoveryService.shouldAttemptRecovery(request.effectiveRole(), request.toolName(), response)) {
            return null;
        }

        log.infof(
                "[AutoHeal-Resume] Detected recoverable sandbox_exec failure in session %s, invoking Shiva debugger",
                request.sessionId());
        String originalCode = extractParam(request.paramsJson(), "code");
        String language = extractParam(request.paramsJson(), "language");
        if (language.isBlank()) {
            log.warnf(
                    "[AutoHeal-Resume] Skipping recovery for session %s because sandbox_exec language is missing",
                    request.sessionId());
            return null;
        }

        ExecutionFailureRecoveryService.RecoveryResult recovery = recoveryService.attemptRecovery(
                request.sessionId(),
                request.context().userId(),
                request.context().chatId(),
                originalCode,
                language,
                failure.output(),
                request.context().channelType(),
                request.context().channelPlatformId(),
                request.context().goal());
        if (!recovery.success()) {
            return null;
        }

        String validationError = recoveryService.validateForExecution(recovery.fixedCode(), language);
        if (validationError != null) {
            log.warnf("[AutoHeal-Resume] Fixed code failed pre-execution validation: %s", validationError);
            return null;
        }

        log.infof(
                "[AutoHeal-Resume] Recovery succeeded, re-executing sandbox_exec with fixed code (attempts=%d)",
                recovery.attemptsUsed());
        String fixedParamsJson = buildFixedParamsJson(request.paramsJson(), "code", recovery.fixedCode());
        ToolResponse retriedResponse;
        try {
            retriedResponse = WorkspaceContext.runWithFullAutonomy(
                    request.sessionId(),
                    request.context().userId(),
                    request.context().chatId(),
                    request.effectiveRole(),
                    request.channelType(),
                    request.context().channelPlatformId(),
                    () -> toolRegistry.invokeTool(request.toolName(), fixedParamsJson, request.sessionId()));
        } catch (Exception e) {
            log.warnf("[AutoHeal-Resume] Re-execution threw exception: %s", LogUtil.rootMessage(e));
            return AgentResult.failed(
                    "Tool execution failed during auto-heal: " + AgentErrorMessageUtil.safeMessage(e),
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(request.history()));
        }

        if (retriedResponse instanceof ToolResponse.ToolSuccess success) {
            executor.recordReasoningStep(
                    request.toolName(),
                    fixedParamsJson,
                    success.output(),
                    true,
                    request.history(),
                    request.sessionId());
            executor.completeInProgressCheckpoint(request.checkpoint());
            return request.stateMachineRunner().runFinishPass(request.context(), reasoningEngine, request.history());
        }
        if (retriedResponse instanceof ToolResponse.ToolFailure retryFailure && !retryFailure.isInfraFailure()) {
            log.warnf("[AutoHeal-Resume] Re-execution failed: %s", retryFailure.output());
            String failMsg =
                    retryFailure.output() != null ? retryFailure.output() : "Tool execution failed after auto-heal";
            return AgentResult.failed(
                    failMsg, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(request.history()));
        }
        return null;
    }

    AgentResult tryEscalation(PendingToolResumeExecutor.PendingToolRequest request, ToolResponse response) {
        if (!(response instanceof ToolResponse.ToolFailure failure)
                || !escalationService.shouldAttemptEscalation(request.effectiveRole(), request.toolName(), response)) {
            return null;
        }

        String targetRole = escalationService.determineEscalationTarget(
                request.effectiveRole(), failure.output(), request.context().goal());
        if (targetRole == null) {
            return null;
        }
        String originalCode = extractParam(request.paramsJson(), "code");
        String language = extractParam(request.paramsJson(), "language");
        PersonaEscalationService.EscalationResult escalation = escalationService.attemptEscalation(
                request.sessionId(),
                request.context().userId(),
                request.context().chatId(),
                request.effectiveRole(),
                targetRole,
                failure.output(),
                request.context().goal(),
                originalCode,
                language,
                request.context().channelType(),
                request.context().channelPlatformId());
        if (escalation.success()) {
            log.infof(
                    "[PersonaEscalation-Resume] Escalation %s succeeded for session %s",
                    targetRole, request.sessionId());
            executor.recordReasoningStep(
                    "escalation:" + request.effectiveRole() + "→" + targetRole,
                    request.paramsJson(),
                    escalation.result(),
                    true,
                    request.history(),
                    request.sessionId());
            executor.completeInProgressCheckpoint(request.checkpoint());
            AgentResult finish =
                    request.stateMachineRunner().runFinishPass(request.context(), reasoningEngine, request.history());
            return withEscalationNotice(finish, request.effectiveRole(), request.toolName(), targetRole);
        }

        log.warnf(
                "[PersonaEscalation-Resume] Escalation failed: %s, returning original failure",
                escalation.errorIfFailed());
        return null;
    }

    /**
     * Surfaces a silent handover to the user: the requested role could not execute the
     * pending tool and a different persona completed the work. The notice is prepended
     * to the finished answer; non-finished results pass through unchanged.
     */
    private AgentResult withEscalationNotice(AgentResult result, String fromRole, String toolName, String toRole) {
        if (!(result instanceof AgentResult.Finished f)
                || f.answer() == null
                || f.answer().contains(UserMessages.ESCALATION_NOTICE_MARKER)) {
            return result;
        }
        String notice = UserMessages.personaEscalationNotice(fromRole, toolName, toRole);
        return new AgentResult.Finished(notice + f.answer(), f.steps(), f.totalInputTokens(), f.totalOutputTokens());
    }

    private String extractParam(String paramsJson, String key) {
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                Map<String, Object> params =
                        objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
                Object value = params.get(key);
                return value instanceof String s ? s : (value != null ? value.toString() : "");
            }
        } catch (Exception e) {
            log.debugf("Failed to extract param '%s' from paramsJson: %s", key, LogUtil.rootMessage(e));
        }
        return "";
    }

    private String buildFixedParamsJson(String originalParamsJson, String key, String newValue) {
        try {
            Map<String, Object> params =
                    objectMapper.readValue(originalParamsJson, new TypeReference<Map<String, Object>>() {});
            params.put(key, newValue);
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warnf("Failed to rebuild paramsJson with fixed code: %s", LogUtil.rootMessage(e));
            return originalParamsJson;
        }
    }
}
