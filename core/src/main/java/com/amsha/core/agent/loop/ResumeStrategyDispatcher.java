package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.state.ToolDecision;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Strategy dispatcher for session resumption. Handles the logic for resuming
 * from various checkpoint states (APPROVAL, PARALLEL_BATCH, etc.) to keep
 * CheckpointResumeHandler focused on orchestration.
 */
class ResumeStrategyDispatcher {

    private static final Logger log = Logger.getLogger(ResumeStrategyDispatcher.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ReasoningEngine reasoningEngine;
    private final ExecutionFailureRecoveryService recoveryService;
    private final PersonaEscalationService escalationService;
    private final ParallelBatchResumeHandler parallelBatchHandler;

    ResumeStrategyDispatcher(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ReasoningEngine reasoningEngine,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.reasoningEngine = reasoningEngine;
        this.recoveryService = recoveryService;
        this.escalationService = escalationService;
        this.parallelBatchHandler = new ParallelBatchResumeHandler(agentStore, toolRegistry, objectMapper);
    }

    AgentResult handle(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            MessageContext messageContext,
            AgentStateMachineRunner stateMachineRunner,
            AgentCheckpoint checkpoint) {

        if (AgentSentinels.GOAL_DENY_APPROVED.equals(context.goal())) {
            return performDenialCleanup(context, history, checkpoint);
        }

        // §Issue 6: Strict Goal Isolation
        String resumeGoal = (messageContext.getGoal() != null
                        && !messageContext.getGoal().isBlank()
                        && !AgentSentinels.GOAL_RESUME_APPROVED.equals(messageContext.getGoal()))
                ? messageContext.getGoal()
                : resolveResumeGoal(checkpoint);

        AgentExecutionContext finalContext = context;
        if (!context.goal().equals(resumeGoal)) {
            log.infof(
                    "[ResumeStrategy] Isolated specific resumption goal for session %s: %s",
                    context.sessionId(), resumeGoal);
            finalContext = new AgentExecutionContext(
                    context.sessionId(),
                    context.userId(),
                    context.chatId(),
                    context.agentRole(),
                    resumeGoal,
                    context.autonomyLevel(),
                    context.structuredOutputMode(),
                    context.maxIterations(),
                    context.startingIteration(),
                    context.channelType(),
                    context.channelPlatformId(),
                    context.systemNote(),
                    context.data());
        }

        String resolvedChannelType = resolveChannelType(finalContext, messageContext);

        if (checkpoint.isParallelBatch()) {
            return parallelBatchHandler.resumeParallelBatch(finalContext, history, checkpoint, resolvedChannelType);
        }

        return executePendingTool(finalContext, history, checkpoint, resolvedChannelType, stateMachineRunner);
    }

    public String resolveResumeGoal(String sessionId, String agentRole) {
        return agentStore
                .load(sessionId, agentRole)
                .map(this::resolveResumeGoal)
                .orElse(AgentSentinels.GOAL_RESUME_APPROVED);
    }

    public String resolveResumeGoal(AgentCheckpoint cp) {
        if (cp.hasBatchToolDecisions()) {
            try {
                Map<String, ToolDecision> decisions = objectMapper.readValue(
                        cp.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
                boolean allDenied =
                        !decisions.isEmpty() && decisions.values().stream().allMatch(ToolDecision.DENIED::equals);
                if (allDenied) return AgentSentinels.GOAL_DENY_APPROVED;
            } catch (Exception _) {
                log.warnf("resolveResumeGoal: failed to parse batch decisions for session %s", cp.getSessionId());
            }
        }
        String og = cp.getOriginalGoal();
        return og != null && !og.isBlank() ? og : AgentSentinels.GOAL_RESUME_APPROVED;
    }

    private AgentResult performDenialCleanup(
            AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint) {
        log.infof("[ResumeStrategy] User denied tool execution for session %s", context.sessionId());
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setFailReason("User denied tool execution.");
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);

        return AgentResult.finished(
                "❌ Tool execution was denied and the task has been cancelled.", List.copyOf(history));
    }

    private AgentResult executePendingTool(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType,
            AgentStateMachineRunner stateMachineRunner) {
        List<ReasoningStep> restoredSteps = agentStore.deserializeSteps(checkpoint);
        if (restoredSteps != null && !restoredSteps.isEmpty()) {
            history.addAll(0, restoredSteps);
            log.infof(
                    "[ResumeStrategy] Prepended %d restored steps from checkpoint for session %s to preserve HITL context",
                    restoredSteps.size(), context.sessionId());
        }
        context.setCheckpointRestoreOffset(history.size());
        String toolName = checkpoint.getPendingToolName();
        String paramsJson = checkpoint.getPendingToolParametersJson();
        String sessionId = context.sessionId();

        String effectiveRole =
                checkpoint.getPendingToolRole() != null ? checkpoint.getPendingToolRole() : context.agentRole();

        log.infof(
                "[ResumeStrategy] Resuming session %s executing pending tool %s as role %s",
                sessionId, toolName, effectiveRole);

        int retryCount = 0;
        int maxRetries = 2;

        while (true) {
            if (Thread.currentThread().isInterrupted() || WorkspaceManager.isSessionHalted(sessionId)) {
                log.warnf("[ResumeStrategy] session %s: halted during tool resume", sessionId);
                throw new RuntimeException("Session " + sessionId + " halted during resume");
            }
            try {
                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                        sessionId,
                        context.userId(),
                        context.chatId(),
                        effectiveRole,
                        channelType,
                        context.channelPlatformId(),
                        () -> toolRegistry.invokeTool(toolName, paramsJson, sessionId));

                if (response instanceof ToolResponse.ToolFailure failure && failure.isInfraFailure()) {
                    return AgentResult.failed(
                            "⚠️ " + failure.output(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                }

                recordReasoningStep(
                        toolName,
                        paramsJson,
                        response.output(),
                        response instanceof ToolResponse.ToolSuccess,
                        history,
                        sessionId);

                if (ToolCapability.INGEST_DOCUMENT.toolName().equals(toolName)) {
                    history.add(ReasoningStep.systemMessage("The document has been ingested successfully. "
                            + "Summarize what was stored for the user in a natural conversational tone."));
                }

                if (response instanceof ToolResponse.ToolSuccess success) {
                    if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(toolName)) {
                        return AgentResult.finished(success.output(), List.copyOf(history));
                    }
                    completeInProgressCheckpoint(checkpoint);
                    return stateMachineRunner.runFinishPass(context, reasoningEngine, history);
                } else {
                    // web_fetch fallback to browser_navigate (same logic as ToolResultProcessor)
                    if (ToolCapability.WEB_FETCH.toolName().equals(toolName)) {
                        String url = extractUrlFromParams(paramsJson);
                        if (url != null && !url.isBlank()) {
                            log.infof(
                                    "[ResumeStrategy] web_fetch failed for %s - falling back to browser_navigate", url);
                            String fallbackGoal = "The web_fetch tool was unable to retrieve " + url
                                    + " (error: " + response.output() + "). "
                                    + "Please try browser_navigate to visit this URL instead.";
                            history.add(ReasoningStep.observeStep(
                                    ToolCapability.WEB_FETCH.toolName(),
                                    "Tool execution failed: " + response.output() + ". Fallback: " + fallbackGoal,
                                    "web_fetch failed, falling back to browser_navigate."));
                            completeInProgressCheckpoint(checkpoint);
                            return stateMachineRunner.run(context.withGoal(fallbackGoal), reasoningEngine, history);
                        }
                    }

                    AgentResult healResult = tryAutoHealOnResume(
                            response,
                            paramsJson,
                            effectiveRole,
                            toolName,
                            sessionId,
                            context,
                            history,
                            channelType,
                            checkpoint,
                            stateMachineRunner);
                    if (healResult != null) {
                        return healResult;
                    }
                    // Persona escalation hook for HITL resume path
                    AgentResult escalationResult = tryEscalationOnResume(
                            response,
                            paramsJson,
                            effectiveRole,
                            toolName,
                            sessionId,
                            context,
                            history,
                            channelType,
                            checkpoint,
                            stateMachineRunner);
                    if (escalationResult != null) {
                        return escalationResult;
                    }
                    String failMsg = response.output() != null ? response.output() : "Tool execution failed";
                    return AgentResult.failed(failMsg, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                }
            } catch (Exception e) {
                long retryAfter = AgentErrorMessageUtil.parseRetryAfterMillis(e);
                if (retryAfter > 0 && retryAfter < 5000 && retryCount < maxRetries) {
                    retryCount++;
                    try {
                        Thread.sleep(retryAfter + 100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Resume interrupted during backoff", ie);
                    }
                    continue;
                }
                return handleResumeExecutionError(checkpoint, toolName, sessionId, history, e);
            }
        }
    }

    private String extractParam(String paramsJson, String key) {
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                Map<String, Object> params =
                        objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
                Object val = params.get(key);
                return val instanceof String s ? s : (val != null ? val.toString() : "");
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

    private AgentResult tryAutoHealOnResume(
            ToolResponse response,
            String paramsJson,
            String effectiveRole,
            String toolName,
            String sessionId,
            AgentExecutionContext context,
            List<ReasoningStep> history,
            String channelType,
            AgentCheckpoint checkpoint,
            AgentStateMachineRunner stateMachineRunner) {
        if (!(response instanceof ToolResponse.ToolFailure failure)
                || !recoveryService.shouldAttemptRecovery(effectiveRole, toolName, response)) {
            return null;
        }

        log.infof(
                "[AutoHeal-Resume] Detected recoverable sandbox_exec failure for session %s, invoking Shiva debugger",
                sessionId);
        String originalCode = extractParam(paramsJson, "code");
        String language = extractParam(paramsJson, "language");
        if (language == null || language.isBlank()) {
            log.warnf(
                    "[AutoHeal-Resume] Skipping recovery for session %s because sandbox_exec language is missing",
                    sessionId);
            return null;
        }

        ExecutionFailureRecoveryService.RecoveryResult recovery = recoveryService.attemptRecovery(
                sessionId,
                context.userId(),
                context.chatId(),
                originalCode,
                language,
                failure.output(),
                context.channelType(),
                context.channelPlatformId(),
                context.goal());

        if (!recovery.success()) {
            return null;
        }

        // Pre-re-execution validation: skip podman if code is obviously broken
        String execValidationError = recoveryService.validateForExecution(recovery.fixedCode(), language);
        if (execValidationError != null) {
            log.warnf("[AutoHeal-Resume] Fixed code failed pre-execution validation: %s", execValidationError);
            return null;
        }

        log.infof(
                "[AutoHeal-Resume] Recovery succeeded, re-executing sandbox_exec with fixed code (attempts=%d)",
                recovery.attemptsUsed());
        String fixedParamsJson = buildFixedParamsJson(paramsJson, "code", recovery.fixedCode());
        ToolResponse retriedResponse;
        try {
            retriedResponse = WorkspaceContext.runWithFullAutonomy(
                    sessionId,
                    context.userId(),
                    context.chatId(),
                    effectiveRole,
                    channelType,
                    context.channelPlatformId(),
                    () -> toolRegistry.invokeTool(toolName, fixedParamsJson, sessionId));
        } catch (Exception e) {
            log.warnf("[AutoHeal-Resume] Re-execution threw exception: %s", LogUtil.rootMessage(e));
            return AgentResult.failed(
                    "Tool execution failed after auto-heal: " + AgentErrorMessageUtil.safeMessage(e),
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(history));
        }

        if (retriedResponse instanceof ToolResponse.ToolSuccess _) {
            recordReasoningStep(toolName, fixedParamsJson, retriedResponse.output(), true, history, sessionId);
            completeInProgressCheckpoint(checkpoint);
            return stateMachineRunner.runFinishPass(context, reasoningEngine, history);
        } else if (retriedResponse instanceof ToolResponse.ToolFailure retryFailure && !retryFailure.isInfraFailure()) {
            log.warnf("[AutoHeal-Resume] Re-execution also failed: %s", retryFailure.output());
            String failMsg =
                    retryFailure.output() != null ? retryFailure.output() : "Tool execution failed after auto-heal";
            return AgentResult.failed(failMsg, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
        }
        return null;
    }

    private AgentResult tryEscalationOnResume(
            ToolResponse response,
            String paramsJson,
            String effectiveRole,
            String toolName,
            String sessionId,
            AgentExecutionContext context,
            List<ReasoningStep> history,
            String _channelType,
            AgentCheckpoint checkpoint,
            AgentStateMachineRunner stateMachineRunner) {
        if (!(response instanceof ToolResponse.ToolFailure failure)
                || !escalationService.shouldAttemptEscalation(effectiveRole, toolName, response)) {
            return null;
        }

        String targetRole =
                escalationService.determineEscalationTarget(effectiveRole, failure.output(), context.goal());
        if (targetRole == null) {
            return null;
        }

        log.infof(
                "[PersonaEscalation-Resume] Detected escalation pattern for session %s, routing %s → %s",
                sessionId, effectiveRole, targetRole);

        String originalCode = extractParam(paramsJson, "code");
        String language = extractParam(paramsJson, "language");

        PersonaEscalationService.EscalationResult escalation = escalationService.attemptEscalation(
                sessionId,
                context.userId(),
                context.chatId(),
                effectiveRole,
                targetRole,
                failure.output(),
                context.goal(),
                originalCode,
                language,
                context.channelType(),
                context.channelPlatformId());

        if (escalation.success()) {
            log.infof("[PersonaEscalation-Resume] Escalation to %s succeeded for session %s", targetRole, sessionId);
            recordReasoningStep(
                    "escalation:" + effectiveRole + "→" + targetRole,
                    paramsJson,
                    escalation.result(),
                    true,
                    history,
                    sessionId);
            completeInProgressCheckpoint(checkpoint);
            return stateMachineRunner.runFinishPass(context, reasoningEngine, history);
        }

        log.warnf(
                "[PersonaEscalation-Resume] Escalation failed: %s, returning original failure",
                escalation.errorIfFailed());
        return null;
    }

    private void recordReasoningStep(
            String tool,
            String paramsJson,
            String output,
            boolean success,
            List<ReasoningStep> history,
            String sessionId) {
        Map<String, Object> params = Map.of();
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warnf("Failed to parse params for session %s", sessionId);
        }
        history.add(ReasoningStep.pending("Executing " + tool + " after human approval.", tool, params, 0, 0)
                .withObservation(output, success));
    }

    private void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }

    private AgentResult handleResumeExecutionError(
            AgentCheckpoint cp, String tool, String sid, List<ReasoningStep> history, Exception e) {
        cp.setStatus(CheckpointStatus.FAILED);
        cp.setPendingToolName(null);
        cp.setPendingToolParametersJson(null);
        agentStore.save(cp);
        String msg = AgentErrorMessageUtil.safeMessage(e);
        log.errorf("Resume tool execution failed session=%s tool=%s: %s", sid, tool, msg);
        return AgentResult.failed(
                "TOOL_EXECUTION_FAILED: " + msg, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
    }

    private String resolveChannelType(AgentExecutionContext context, MessageContext messageContext) {
        if (messageContext != null
                && messageContext.getUser() != null
                && messageContext.getUser().channel() != null
                && messageContext.getUser().channel().id() != null
                && !messageContext.getUser().channel().id().isBlank()) {
            return messageContext.getUser().channel().id();
        }
        if (context != null
                && context.channelType() != null
                && !context.channelType().isBlank()) {
            return context.channelType();
        }
        return "unknown";
    }

    /** Extracts the "url" value from a JSON params string. Returns null if not found. */
    private static String extractUrlFromParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) return null;
        // Simple extraction — avoids full JSON parsing for a single field
        int idx = paramsJson.indexOf("\"url\"");
        if (idx < 0) return null;
        int colon = paramsJson.indexOf(':', idx + 5);
        if (colon < 0) return null;
        int start = paramsJson.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = paramsJson.indexOf('"', start + 1);
        if (end < 0) return null;
        return paramsJson.substring(start + 1, end);
    }
}
