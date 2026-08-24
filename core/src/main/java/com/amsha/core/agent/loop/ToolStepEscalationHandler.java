package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

import com.amsha.tool.api.ToolResponse;

import org.jboss.logging.Logger;

/**
 * Handles persona escalation after tool failures, including the auto-heal recovery path.
 * Extracted from {@link ToolStepHandler} to keep that class under PMD size limits.
 */
@ApplicationScoped
public class ToolStepEscalationHandler {

    private static final Logger log = Logger.getLogger(ToolStepEscalationHandler.class);

    private final PersonaEscalationService escalationService;

    @Inject
    public ToolStepEscalationHandler(PersonaEscalationService escalationService) {
        this.escalationService = escalationService;
    }

    /**
     * Attempts escalation after auto-heal re-execution failed.
     * Returns the escalation result, or the original retry failure if escalation is not possible.
     */
    ToolResponse attemptEscalationAfterHealFailure(
            AgentExecutionContext context, Map<String, Object> params, ToolResponse.ToolFailure retryFailure) {
        String targetRole =
                escalationService.determineEscalationTarget(context.agentRole(), retryFailure.output(), context.goal());
        if (targetRole != null) {
            log.infof(
                    "[PersonaEscalation] Auto-heal failed, escalating %s → %s for session %s",
                    context.agentRole(), targetRole, context.sessionId());
            return attemptEscalation(context, params, retryFailure, targetRole);
        }
        return retryFailure;
    }

    /**
     * Attempts to escalate a tool failure to another persona.
     * Returns the escalation result, or the original failure if escalation fails.
     */
    ToolResponse attemptEscalation(
            AgentExecutionContext context,
            Map<String, Object> params,
            ToolResponse.ToolFailure failure,
            String targetRole) {
        String originalCode = extractParam(params, "code");
        String language = extractParam(params, "language");
        String originalGoal = context.goal();

        PersonaEscalationService.EscalationResult escalation = escalationService.attemptEscalation(
                context.sessionId(),
                context.userId(),
                context.chatId(),
                context.agentRole(),
                targetRole,
                failure.output(),
                originalGoal,
                originalCode,
                language,
                context.channelType(),
                context.channelPlatformId());

        if (escalation.success()) {
            log.infof("[PersonaEscalation] Escalation succeeded, returning result from %s", escalation.targetPersona());
            return ToolResponse.success(escalation.result());
        }

        log.warnf("[PersonaEscalation] Escalation failed: %s, returning original failure", escalation.errorIfFailed());
        return failure;
    }

    /**
     * Attempts a non-recovery persona escalation for a tool failure.
     * Returns the escalation response, or {@code null} if escalation is not applicable or fails.
     */
    ToolResponse attemptGenericEscalation(
            AgentExecutionContext context,
            String toolName,
            Map<String, Object> params,
            ToolResponse.ToolFailure failure) {
        if (!escalationService.shouldAttemptEscalation(context.agentRole(), toolName, failure)) {
            return null;
        }
        String targetRole =
                escalationService.determineEscalationTarget(context.agentRole(), failure.output(), context.goal());
        if (targetRole != null) {
            log.infof(
                    "[PersonaEscalation] Detected escalation pattern for session %s, routing %s → %s",
                    context.sessionId(), context.agentRole(), targetRole);
            return attemptEscalation(context, params, failure, targetRole);
        }
        return null;
    }

    private String extractParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : (val != null ? val.toString() : "");
    }
}
