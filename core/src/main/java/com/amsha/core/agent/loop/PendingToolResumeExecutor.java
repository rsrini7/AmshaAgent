package com.amsha.core.agent.loop;

import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.message.UserMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/** Executes one non-parallel tool stored in an approval checkpoint. */
final class PendingToolResumeExecutor {
    private static final Logger log = Logger.getLogger(PendingToolResumeExecutor.class);
    private static final int MAX_RETRIES = 2;

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
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
        this.reasoningEngine = reasoningEngine;
        this.delegationResolver = delegationResolver;
        this.guard = guard;
        this.failureHandler = new PendingToolFailureHandler(
                objectMapper, toolRegistry, reasoningEngine, recoveryService, escalationService, this);
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
            return stateMachineRunner.runFinishPass(context, reasoningEngine, history);
        }

        var owner = delegationResolver.resolveOwner(effectiveRole, toolName);
        if (owner.isPresent()) {
            toolName = ToolCapability.DELEGATE_TO_AGENT.toolName();
            paramsJson = buildDelegationParams(originalToolName, paramsJson, owner.get());
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
                sessionId,
                effectiveRole));
    }

    private void restoreCheckpointHistory(
            AgentExecutionContext context, List<ReasoningStep> history, AgentCheckpoint checkpoint) {
        List<ReasoningStep> restoredSteps = agentStore.deserializeSteps(checkpoint);
        if (restoredSteps != null && !restoredSteps.isEmpty()) {
            history.addAll(0, restoredSteps);
            log.infof(
                    "[ResumeStrategy] Prepended %d restored steps from checkpoint session %s to preserve HITL context",
                    restoredSteps.size(), context.sessionId());
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
                if (retryAfter > 0 && retryAfter < 5000 && retryCount < MAX_RETRIES) {
                    retryCount++;
                    waitBeforeRetry(retryAfter);
                    continue;
                }
                return handleResumeExecutionError(request, e);
            }
        }
    }

    private AgentResult executeAttempt(PendingToolRequest request) throws Exception {
        Map<String, Object> params = parseParams(request.paramsJson());
        ToolInvocationGuard.GuardDecision decision = guard == null
                ? new ToolInvocationGuard.GuardDecision.Allow()
                : guard.evaluate(
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

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                request.sessionId(),
                request.context().userId(),
                request.context().chatId(),
                request.effectiveRole(),
                request.channelType(),
                request.context().channelPlatformId(),
                () -> toolRegistry.invokeTool(request.toolName(), request.paramsJson(), request.sessionId()));
        if (response instanceof ToolResponse.ToolFailure failure && failure.isInfraFailure()) {
            return AgentResult.failed(
                    "⚠️ " + failure.output(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(request.history()));
        }

        recordReasoningStep(
                request.toolName(),
                request.paramsJson(),
                response.output(),
                response instanceof ToolResponse.ToolSuccess,
                request.history(),
                request.sessionId());
        if (ToolCapability.INGEST_DOCUMENT.toolName().equals(request.originalToolName())) {
            request.history()
                    .add(ReasoningStep.systemMessage("The document ingested successfully. "
                            + "Summarize what was stored for the user in a natural conversational tone."));
        }
        if (response instanceof ToolResponse.ToolSuccess success) {
            if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(request.toolName())) {
                String safeOutput = UserMessages.stripInternalDirectiveTail(success.output());
                return AgentResult.finished(safeOutput, List.copyOf(request.history()));
            }
            completeInProgressCheckpoint(request.checkpoint());
            return request.stateMachineRunner().runFinishPass(request.context(), reasoningEngine, request.history());
        }
        return handleFailure(request, response);
    }

    private Map<String, Object> parseParams(String paramsJson) throws Exception {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
    }

    private AgentResult handleFailure(PendingToolRequest request, ToolResponse response) {
        if (ToolCapability.WEB_FETCH.toolName().equals(request.toolName())) {
            String url = extractUrlFromParams(request.paramsJson());
            if (url != null && !url.isBlank()) {
                String fallbackGoal = "The web_fetch tool was unable to retrieve "
                        + url
                        + " (error: "
                        + response.output()
                        + "). Please try browser_navigate to visit this URL instead.";
                log.infof("[ResumeStrategy] web_fetch failed for %s - falling back to browser_navigate", url);
                request.history()
                        .add(ReasoningStep.observeStep(
                                ToolCapability.WEB_FETCH.toolName(),
                                "Tool execution failed: " + response.output() + ". Fallback: " + fallbackGoal,
                                "web_fetch failed, falling back to browser_navigate."));
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

    private AgentResult handleResumeExecutionError(PendingToolRequest request, Exception e) {
        AgentCheckpoint checkpoint = request.checkpoint();
        checkpoint.setStatus(CheckpointStatus.FAILED);
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        agentStore.save(checkpoint);
        String message = AgentErrorMessageUtil.safeMessage(e);
        log.errorf(
                "Resume tool execution failed for session=%s tool=%s: %s",
                request.sessionId(), request.toolName(), message);
        return AgentResult.failed(
                "TOOL_EXECUTION_FAILED: " + message,
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(request.history()));
    }

    private String buildDelegationParams(String toolName, String paramsJson, String owner) {
        Map<String, Object> params = Map.of();
        if (paramsJson != null && !paramsJson.isBlank()) {
            try {
                params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warnf("[ResumeStrategy] Could not parse pending %s: %s", toolName, LogUtil.rootMessage(e));
            }
        }
        try {
            return objectMapper.writeValueAsString(
                    Map.of("agent", owner, "goal", ToolStepResolutionUtils.buildDelegateGoal(toolName, params)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build delegation request for " + toolName, e);
        }
    }

    void recordReasoningStep(
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
            log.warnf("Failed to parse resumed tool params for session %s", sessionId);
        }
        history.add(ReasoningStep.pending("Executing " + tool + " after human approval.", tool, params, 0, 0)
                .withObservation(output, success));
    }

    void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }

    private static String extractUrlFromParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return null;
        }
        int keyIndex = paramsJson.indexOf("\"url\"");
        int colon = keyIndex < 0 ? -1 : paramsJson.indexOf(':', keyIndex + 5);
        int firstQuote = colon < 0 ? -1 : paramsJson.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : paramsJson.indexOf('"', firstQuote + 1);
        return secondQuote > firstQuote ? paramsJson.substring(firstQuote + 1, secondQuote) : null;
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
            String sessionId,
            String effectiveRole) {}
}
