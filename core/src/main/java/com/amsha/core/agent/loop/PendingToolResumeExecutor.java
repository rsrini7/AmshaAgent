package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.message.UserMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Executes one non-parallel tool stored in an approval checkpoint. */
final class PendingToolResumeExecutor {
    private static final Logger log = Logger.getLogger(PendingToolResumeExecutor.class);
    private static final int MAX_RETRIES = 2;
    private static final int MAX_INLINE_RETRY_AFTER_MS = 5_000;

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final PendingToolResumeSupport resumeSupport;
    private final ReasoningEngine reasoningEngine;
    private final ToolDelegationResolver delegationResolver;
    private final ToolInvocationGuard guard;
    private final PendingToolFailureHandler failureHandler;

    PendingToolResumeExecutor(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ReasoningEngine reasoningEngine,
            ExecutionFailureRecoveryService recoveryService,
            PersonaEscalationService escalationService,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.resumeSupport = new PendingToolResumeSupport(objectMapper, agentStore);
        this.reasoningEngine = reasoningEngine;
        this.delegationResolver = delegationResolver;
        this.guard = guard;
        this.failureHandler =
                new PendingToolFailureHandler(objectMapper, reasoningEngine, recoveryService, escalationService, this);
    }

    AgentResult execute(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType,
            AgentStateMachineRunner stateMachineRunner) {
        restoreCheckpointHistory(context, history, checkpoint);

        String toolName = checkpoint.getPendingToolName();
        String originalToolName = toolName;
        String paramsJson = checkpoint.getPendingToolParametersJson();
        String sessionId = context.sessionId();
        String effectiveRole =
                checkpoint.getPendingToolRole() != null ? checkpoint.getPendingToolRole() : context.agentRole();

        if (toolName == null || toolName.isBlank()) {
            log.warnf(
                    "[ResumeStrategy] No pending tool found in checkpoint for session %s role %s — completing checkpoint and running finish pass",
                    sessionId, effectiveRole);
            completeInProgressCheckpoint(checkpoint);
            return runFinishPassWithRetry(stateMachineRunner, context, history, sessionId, null);
        }

        Map<String, Object> pendingParams = resumeSupport.parsePausedParams(paramsJson);
        String operationId = PendingToolOperationIdLookup.find(history, toolName, pendingParams)
                .orElse(null);
        if (operationId == null) {
            String reason = "Pending tool checkpoint is missing its operation identity.";
            agentStore.markFailed(checkpoint, reason);
            return AgentResult.failed(reason, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
        }

        var owner = delegationResolver.resolveOwner(effectiveRole, toolName);
        if (owner.isPresent()) {
            toolName = ToolCapability.DELEGATE_TO_AGENT.toolName();
            paramsJson = resumeSupport.buildDelegationParams(originalToolName, paramsJson, owner.get());
            try {
                operationId = resumeSupport
                        .persistReplacementOperation(
                                checkpoint,
                                history,
                                operationId,
                                toolName,
                                paramsJson,
                                effectiveRole,
                                CheckpointStatus.IN_PROGRESS,
                                "Pending action redirected to the authorized delegated tool.")
                        .operationId();
            } catch (Exception e) {
                String reason = "Delegated tool identity could not be persisted.";
                agentStore.markFailed(checkpoint, reason);
                return AgentResult.failed(reason, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
            }
            log.warnf(
                    "[ResumeStrategy] Redirecting unauthorized pending tool %s from role %s to %s in session %s",
                    originalToolName, effectiveRole, owner.get(), sessionId);
        }
        log.infof(
                "[ResumeStrategy] Resuming session %s executing pending tool %s as role %s",
                sessionId, toolName, effectiveRole);

        return executeWithRetries(new PendingToolRequest(
                context,
                history,
                checkpoint,
                channelType,
                stateMachineRunner,
                toolName,
                originalToolName,
                paramsJson,
                operationId,
                sessionId,
                effectiveRole));
    }

    private void restoreCheckpointHistory(
            AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint) {
        List<ReasoningStep> restoredSteps = agentStore.deserializeSteps(checkpoint);
        if (restoredSteps != null && !restoredSteps.isEmpty()) {
            boolean historyAlreadyRestored = history.size() >= restoredSteps.size()
                    && history.subList(0, restoredSteps.size()).equals(restoredSteps);
            if (!historyAlreadyRestored) {
                history.addAll(0, restoredSteps);
                log.infof(
                        "[ResumeStrategy] Prepended %d restored steps from checkpoint session %s to preserve HITL context",
                        restoredSteps.size(), context.sessionId());
            }
        }
        context.setCheckpointRestoreOffset(history.size());
    }

    private AgentResult executeWithRetries(PendingToolRequest request) {
        int retryCount = 0;
        while (true) {
            failIfHalted(request.sessionId());
            try {
                return executeAttempt(request);
            } catch (Exception e) {
                long retryAfter = AgentErrorMessageUtil.parseRetryAfterMillis(e);
                if (retryAfter > 0 && retryAfter < MAX_INLINE_RETRY_AFTER_MS && retryCount < MAX_RETRIES) {
                    retryCount++;
                    waitBeforeRetry(retryAfter);
                    continue;
                }
                return handleResumeExecutionError(request, e);
            }
        }
    }

    private AgentResult executeAttempt(PendingToolRequest request) throws Exception {
        Map<String, Object> params = resumeSupport.parseParams(request.paramsJson());
        if (guard == null) {
            log.errorf("[ResumeStrategy] Mandatory tool guard unavailable for session %s", request.sessionId());
            return AgentResult.failed(
                    "Tool execution blocked because the safety guard is unavailable.",
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(request.history()));
        }
        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                request.sessionId(),
                request.effectiveRole(),
                request.context().autonomyLevel(),
                request.toolName(),
                params,
                request.history(),
                request.context().goal(),
                List.of());
        if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked blocked) {
            return AgentResult.failed(
                    "Tool execution blocked by safety guard: " + blocked.observation(),
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(request.history()));
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval pause) {
            return AgentResult.awaitingApproval(pause.frame(), request.sessionId(), List.copyOf(request.history()));
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.DurableReplay replay) {
            recordReasoningStep(
                    request.toolName(),
                    request.paramsJson(),
                    replay.observation(),
                    replay.succeeded(),
                    replay.errorCode(),
                    request.operationId(),
                    request.history(),
                    request.sessionId());
            completeInProgressCheckpoint(request.checkpoint());
            return runFinishPassWithRetry(
                    request.stateMachineRunner(),
                    request.context(),
                    request.history(),
                    request.sessionId(),
                    request.toolName());
        }
        if (decision instanceof ToolInvocationGuard.GuardDecision.Deduplicate deduplicate) {
            recordReasoningStep(
                    request.toolName(),
                    request.paramsJson(),
                    deduplicate.cachedObservation(),
                    true,
                    null,
                    request.operationId(),
                    request.history(),
                    request.sessionId());
            completeInProgressCheckpoint(request.checkpoint());
            return runFinishPassWithRetry(
                    request.stateMachineRunner(),
                    request.context(),
                    request.history(),
                    request.sessionId(),
                    request.toolName());
        }

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                request.sessionId(),
                request.context().userId(),
                request.context().chatId(),
                request.effectiveRole(),
                request.channelType(),
                request.context().channelPlatformId(),
                () -> toolRegistry.invokeToolWithOperationId(
                        request.toolName(), request.paramsJson(), request.sessionId(), request.operationId()));
        if (response instanceof ToolResponse.ToolFailure failure && failure.isInfraFailure()) {
            return AgentResult.failed(
                    "⚠️ " + failure.output(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(request.history()));
        }

        recordReasoningStep(
                request.toolName(),
                request.paramsJson(),
                response.output(),
                response instanceof ToolResponse.ToolSuccess,
                null,
                request.operationId(),
                request.history(),
                request.sessionId());
        if (ToolCapability.INGEST_DOCUMENT.toolName().equals(request.originalToolName())) {
            request.history()
                    .add(ReasoningStep.systemMessage("The document ingested successfully. "
                            + "Summarize what was stored for the user in a natural conversational tone."));
        }
        if (response instanceof ToolResponse.ToolSuccess success) {
            if (success.output() != null && success.output().contains(UserMessages.DELEGATION_PAUSED_MARKER)) {
                String pausedTool = UserMessages.extractPausedTool(success.output());
                String pausedParamsJson = UserMessages.extractPausedParamsJson(success.output());
                Map<String, Object> pausedParams = resumeSupport.parsePausedParams(pausedParamsJson);
                if (pausedTool == null || pausedTool.isBlank() || pausedParamsJson == null || pausedParams == null) {
                    return AgentResult.failed(
                            "Delegated approval payload was invalid and was not resumed.",
                            AgentResult.FailureCategory.SYSTEM_ERROR,
                            List.copyOf(request.history()));
                }
                if (!resumeSupport.persistDelegatedWait(
                        request.checkpoint(),
                        request.sessionId(),
                        request.originalToolName(),
                        request.effectiveRole(),
                        request.paramsJson(),
                        request.history())) {
                    return AgentResult.failed(
                            "Could not persist delegated wait state.",
                            AgentResult.FailureCategory.SYSTEM_ERROR,
                            List.copyOf(request.history()));
                }
                ApprovalFrame frame = new ApprovalFrame(pausedTool, pausedParams, RiskLevel.HIGH);
                return AgentResult.awaitingApproval(frame, request.sessionId(), List.copyOf(request.history()));
            }
            if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(request.toolName())) {
                completeInProgressCheckpoint(request.checkpoint());
                String safeOutput = UserMessages.stripInternalDirectiveTail(success.output());
                return AgentResult.finished(safeOutput, List.copyOf(request.history()));
            }
            if (ToolCapability.SCHEDULE_TASK.toolName().equals(request.toolName())) {
                String safeOutput = UserMessages.stripInternalDirectiveTail(success.output());
                completeInProgressCheckpoint(request.checkpoint());
                return AgentResult.finished("✅ " + safeOutput, List.copyOf(request.history()));
            }
            completeInProgressCheckpoint(request.checkpoint());
            return runFinishPassWithRetry(
                    request.stateMachineRunner(),
                    request.context(),
                    request.history(),
                    request.sessionId(),
                    request.toolName());
        }
        return handleFailure(request, response);
    }

    /**
     * Keep the sequential parent alive while its child owns the approval.
     * The parent delegation was already approved; storing it as IN_PROGRESS
     * prevents stale-approval cleanup and a second parent approval from
     * launching a duplicate child. The child checkpoint remains the source of
     * truth for the pending tool approval.
     */
    private AgentResult handleFailure(PendingToolRequest request, ToolResponse response) {
        String url = resumeSupport.extractUrlFromParams(request.paramsJson());
        if (response != null && !response.success() && url != null && !url.isBlank()) {
            String fallbackGoal = null;
            String fallbackMessage = null;
            if (WebRetrievalFallbackPolicy.isBrowser(request.toolName())) {
                fallbackGoal = WebRetrievalFallbackPolicy.browserToFetchGoal(url, response.output());
                fallbackMessage = "Browser navigation failed, falling back to web_fetch.";
                request.history()
                        .add(ReasoningStep.observeStep(
                                ToolCapability.BROWSER_NAVIGATE.toolName(),
                                WebRetrievalFallbackPolicy.browserFailureMarker(url)
                                        + ". Tool execution failed: "
                                        + response.output()
                                        + ". Fallback: "
                                        + fallbackGoal,
                                fallbackMessage));
            } else if (WebRetrievalFallbackPolicy.isWebFetch(request.toolName())) {
                boolean browserFailed = WebRetrievalFallbackPolicy.browserAttemptFailed(request.history(), url);
                fallbackGoal = browserFailed
                        ? WebRetrievalFallbackPolicy.fetchToSearchGoal(url, response.output())
                        : WebRetrievalFallbackPolicy.fetchToBrowserGoal(url, response.output());
                fallbackMessage = browserFailed
                        ? "Browser and web_fetch failed, falling back to one search."
                        : "web_fetch failed, falling back to browser_navigate.";
                request.history()
                        .add(ReasoningStep.observeStep(
                                ToolCapability.WEB_FETCH.toolName(),
                                "Tool execution failed: " + response.output() + ". Fallback: " + fallbackGoal,
                                fallbackMessage));
            }
            if (fallbackGoal != null) {
                log.infof(
                        "[ResumeStrategy] %s failed for %s - falling back with bounded retrieval policy",
                        request.toolName(), url);
                completeInProgressCheckpoint(request.checkpoint());
                return request.stateMachineRunner()
                        .run(request.context().withGoal(fallbackGoal), reasoningEngine, request.history());
            }
        }
        AgentResult healResult = failureHandler.tryAutoHeal(request, response);
        if (healResult != null) {
            return healResult;
        }
        AgentResult escalationResult = failureHandler.tryEscalation(request, response);
        if (escalationResult != null) {
            return escalationResult;
        }
        String failMsg = response.output() != null ? response.output() : "Tool execution failed";
        return AgentResult.failed(failMsg, AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(request.history()));
    }

    private void failIfHalted(String sessionId) {
        if (Thread.currentThread().isInterrupted() || WorkspaceManager.isSessionHalted(sessionId)) {
            log.warnf("[ResumeStrategy] session %s: halted during tool resume", sessionId);
            throw new RuntimeException("Session " + sessionId + " halted during resume");
        }
    }

    private void waitBeforeRetry(long retryAfter) {
        try {
            Thread.sleep(retryAfter + 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Resume interrupted during backoff", e);
        }
    }

    private AgentResult runFinishPassWithRetry(
            AgentStateMachineRunner stateMachineRunner,
            AgentExecutionContext context,
            List<ReasoningStep> history,
            String sessionId,
            String toolName) {
        try {
            return stateMachineRunner.runFinishPass(context, reasoningEngine, history);
        } catch (RuntimeException e) {
            long retryAfterMs = AgentErrorMessageUtil.parseRetryAfterMillis(e);
            if (retryAfterMs <= 0) {
                throw e;
            }

            int retryDelayMs = (int) Math.min(retryAfterMs, Integer.MAX_VALUE);
            log.warnf(
                    "Post-approval finish pass rate limited session=%s tool=%s retryAfterMs=%d",
                    sessionId, toolName, retryDelayMs);
            return AgentResult.awaitingRetry(sessionId, retryDelayMs, List.copyOf(history));
        }
    }

    private AgentResult handleResumeExecutionError(PendingToolRequest request, Exception e) {
        return resumeSupport.handleResumeExecutionError(request, e);
    }

    void recordReasoningStep(
            String tool,
            String paramsJson,
            String output,
            boolean success,
            List<ReasoningStep> history,
            String sessionId) {
        recordReasoningStep(tool, paramsJson, output, success, null, null, history, sessionId);
    }

    void recordReasoningStep(
            String tool,
            String paramsJson,
            String output,
            boolean success,
            String errorCode,
            List<ReasoningStep> history,
            String sessionId) {
        recordReasoningStep(tool, paramsJson, output, success, errorCode, null, history, sessionId);
    }

    void recordReasoningStep(
            String tool,
            String paramsJson,
            String output,
            boolean success,
            String errorCode,
            String operationId,
            List<ReasoningStep> history,
            String sessionId) {
        Map<String, Object> params = Map.of();
        try {
            if (paramsJson != null && !paramsJson.isBlank()) {
                params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warnf("Failed to parse resumed tool params for session %s", sessionId);
        }
        ReasoningStep pending =
                ReasoningStep.pending("Executing " + tool + " after human approval.", tool, params, 0, 0);
        if (operationId != null) {
            pending = pending.withOperationId(operationId);
        }
        ReasoningStep completed = pending.withDiagnostics(output, success, errorCode, null, null);
        if (operationId != null) {
            OptionalInt pendingIndex = PendingToolOperationIdLookup.findUniquePendingIndex(history, operationId);
            if (pendingIndex.isPresent()) {
                history.set(pendingIndex.getAsInt(), completed);
                return;
            }
        }
        history.add(completed);
    }

    void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }

    PendingToolResumeSupport.Replacement persistReplacementOperation(
            PendingToolRequest request,
            String toolName,
            String paramsJson,
            CheckpointStatus status,
            String previousObservation)
            throws Exception {
        return resumeSupport.persistReplacementOperation(request, toolName, paramsJson, status, previousObservation);
    }

    record PendingToolRequest(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType,
            AgentStateMachineRunner stateMachineRunner,
            String toolName,
            String originalToolName,
            String paramsJson,
            String operationId,
            String sessionId,
            String effectiveRole) {}
}
