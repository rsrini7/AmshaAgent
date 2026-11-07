package com.amsha.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.message.UserMessages;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

class ParallelBatchResumeHandler {

    private static final Logger log = Logger.getLogger(ParallelBatchResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ToolDelegationResolver delegationResolver;
    private final ToolInvocationGuard guard;
    private final ParallelBatchAttemptReconciler reconciler;

    ParallelBatchResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard) {
        this(agentStore, toolRegistry, objectMapper, delegationResolver, guard, null);
    }

    ParallelBatchResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard,
            ParallelBatchAttemptReconciler reconciler) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.delegationResolver = delegationResolver;
        this.guard = guard;
        this.reconciler = reconciler;
    }

    AgentResult resumeParallelBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType) {

        String sessionId = context.sessionId();
        log.infof("[ResumeStrategy] Resuming parallel batch for session %s", sessionId);

        List<ParallelToolCall> calls = parseBatchCalls(checkpoint);
        if (calls == null) {
            return handleCorruptBatch(checkpoint, history);
        }

        Map<String, ToolDecision> decisions = parseDecisions(checkpoint);
        Map<String, JsonNode> preExecuted = parsePreExecuted(checkpoint);

        List<ParallelToolCall> completed = new ArrayList<>();
        List<String> attemptIds = new ArrayList<>();
        String workspaceId = WorkspaceContext.activeWorkspaceId().orElse(null);

        for (int i = 0; i < calls.size(); i++) {
            ParallelToolCall call = calls.get(i);
            ChildOutcome outcome = resolveOrExecuteChild(
                    context, checkpoint, history, channelType, call, i, calls, decisions, preExecuted, workspaceId);
            switch (outcome) {
                case ChildOutcome.Completed c -> {
                    completed.add(c.call());
                    c.attemptId().ifPresent(attemptIds::add);
                }
                case ChildOutcome.Pause p -> {
                    checkpoint.setStatus(CheckpointStatus.AWAITING_APPROVAL);
                    agentStore.save(checkpoint);
                    return AgentResult.awaitingApproval(p.frame(), sessionId, List.copyOf(history));
                }
                case ChildOutcome.Blocked ignored -> {
                    return handleCorruptBatch(checkpoint, history);
                }
            }
        }

        Optional<AgentResult> delegatedPauseResult = handleDelegatedPause(checkpoint, history, sessionId, completed);
        if (delegatedPauseResult.isPresent()) {
            return delegatedPauseResult.get();
        }
        if (reconciler != null && workspaceId != null && checkpoint.getId() != null) {
            return reconciler.appendAggregateResult(
                    workspaceId, checkpoint, history, calls, completed, attemptIds, agentStore);
        }
        return completeBatch(checkpoint, history, completed);
    }

    private List<ParallelToolCall> parseBatchCalls(AgentCheckpoint checkpoint) {
        try {
            return objectMapper.readValue(
                    checkpoint.getPendingToolParametersJson(), new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[ResumeStrategy] session=%s: failed to parse batch", checkpoint.getSessionId());
            return null;
        }
    }

    private Map<String, ToolDecision> parseDecisions(AgentCheckpoint checkpoint) {
        if (!checkpoint.hasBatchToolDecisions()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    checkpoint.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
        } catch (Exception e) {
            log.debugf("Batch decision parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            return Map.of();
        }
    }

    private Map<String, JsonNode> parsePreExecuted(AgentCheckpoint checkpoint) {
        if (!checkpoint.hasPreExecutedResults()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    checkpoint.getPreExecutedResults(), new TypeReference<Map<String, JsonNode>>() {});
        } catch (Exception e) {
            log.debugf("Pre-executed result parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            return Map.of();
        }
    }

    private ChildOutcome resolveOrExecuteChild(
            AgentExecutionContext context,
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            String channelType,
            ParallelToolCall call,
            int index,
            List<ParallelToolCall> calls,
            Map<String, ToolDecision> decisions,
            Map<String, JsonNode> preExecuted,
            String workspaceId) {
        if (call.operationId() == null) {
            return new ChildOutcome.Completed(
                    call.withResult("Tool execution blocked because this checkpoint has no operation identity.", false),
                    Optional.empty());
        }
        if (reconciler != null && workspaceId != null) {
            var outcome = reconciler.reconcileChild(workspaceId, context.sessionId(), call);
            if (outcome instanceof ParallelBatchAttemptReconciler.ChildOutcome.Settled settled) {
                log.infof(
                        "[ResumeStrategy] Child %s already settled (%s); skipping rerun",
                        call.toolName(), settled.attempt().status());
                return new ChildOutcome.Completed(
                        settled.completedCall(), Optional.of(settled.attempt().attemptId()));
            }
            if (outcome instanceof ParallelBatchAttemptReconciler.ChildOutcome.Blocked blocked) {
                log.errorf("[ResumeStrategy] Child %s reconciliation blocked: %s", call.toolName(), blocked.reason());
                return new ChildOutcome.Blocked();
            }
        }
        Optional<ParallelToolCall> preExecCall = resolvePreExecuted(call, preExecuted);
        if (preExecCall.isPresent()) {
            return new ChildOutcome.Completed(preExecCall.get(), Optional.empty());
        }
        ToolDecision toolDecision = resolveDecision(call, index, calls, decisions);
        if (toolDecision == ToolDecision.DENIED) {
            return new ChildOutcome.Completed(
                    call.withResult("User denied this tool execution.", false), Optional.empty());
        }
        return executeChild(context, checkpoint, history, channelType, call, workspaceId);
    }

    private Optional<ParallelToolCall> resolvePreExecuted(ParallelToolCall call, Map<String, JsonNode> preExecuted) {
        String preExecutedKey = preExecuted.containsKey(call.callId()) ? call.callId() : call.toolName();
        if (!preExecuted.containsKey(preExecutedKey)) {
            return Optional.empty();
        }
        try {
            var storedNode = preExecuted.get(preExecutedKey);
            ToolResponse stored = storedNode.isTextual()
                    ? objectMapper.readValue(storedNode.asText(), ToolResponse.class)
                    : objectMapper.treeToValue(storedNode, ToolResponse.class);
            return Optional.of(
                    switch (stored) {
                        case ToolResponse.ToolSuccess success -> call.withResult(success.output(), true);
                        case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                        case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                    });
        } catch (Exception e) {
            log.debugf("Pre-executed tool response parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            return Optional.empty();
        }
    }

    private ToolDecision resolveDecision(
            ParallelToolCall call, int index, List<ParallelToolCall> calls, Map<String, ToolDecision> decisions) {
        String decisionKey = BatchDecisionUtil.decisionKey(call, index + 1, calls);
        ToolDecision toolDecision = decisions.get(decisionKey);
        if (toolDecision == null && call.callId() != null) {
            toolDecision = decisions.get(call.callId());
        }
        if (toolDecision == null) {
            toolDecision = decisions.getOrDefault(call.toolName(), ToolDecision.PENDING);
        }
        return toolDecision;
    }

    private ChildOutcome executeChild(
            AgentExecutionContext context,
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            String channelType,
            ParallelToolCall call,
            String workspaceId) {
        ParallelToolCall executionCall = call;
        try {
            String effectiveRole =
                    checkpoint.getPendingToolRole() != null ? checkpoint.getPendingToolRole() : context.agentRole();
            String toolName = call.toolName();
            String paramsJson = call.paramsJson();
            var owner = delegationResolver.resolveOwner(effectiveRole, toolName);
            if (owner.isPresent()) {
                String originalToolName = toolName;
                toolName = ToolCapability.DELEGATE_TO_AGENT.toolName();
                paramsJson = buildDelegationParams(originalToolName, paramsJson, owner.get());
                log.warnf(
                        "[ResumeStrategy] Redirecting unauthorized batch tool %s from role %s to %s in session %s",
                        originalToolName, effectiveRole, owner.get(), context.sessionId());
            }
            final String invocationToolName = toolName;
            final String invocationParamsJson = paramsJson;
            final String invocationOperationId = call.operationId();
            if (!invocationToolName.equals(call.toolName())
                    || !Objects.equals(invocationParamsJson, call.paramsJson())) {
                executionCall = new ParallelToolCall(
                        call.callId(),
                        invocationToolName,
                        Map.of(),
                        invocationParamsJson,
                        null,
                        false,
                        call.operationId());
            }

            var parsedParams = invocationParamsJson != null && !invocationParamsJson.isBlank()
                    ? objectMapper.readValue(invocationParamsJson, new TypeReference<Map<String, Object>>() {})
                    : Map.<String, Object>of();
            executionCall = new ParallelToolCall(
                    call.callId(),
                    invocationToolName,
                    parsedParams,
                    invocationParamsJson,
                    null,
                    false,
                    call.operationId());

            ToolInvocationGuard.GuardDecision decision =
                    evaluateGuard(context, effectiveRole, invocationToolName, parsedParams, history);

            if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
                return new ChildOutcome.Completed(
                        executionCall.withResult("Tool execution blocked by safety guard: " + b.observation(), false),
                        Optional.empty());
            }
            if (decision instanceof ToolInvocationGuard.GuardDecision.PauseForApproval pause) {
                return new ChildOutcome.Pause(pause.frame());
            }

            ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                    context.sessionId(),
                    context.userId(),
                    context.chatId(),
                    effectiveRole,
                    channelType,
                    context.channelPlatformId(),
                    () -> toolRegistry.invokeToolWithOperationId(
                            invocationToolName, invocationParamsJson, context.sessionId(), invocationOperationId));

            ParallelToolCall completedCall =
                    switch (response) {
                        case ToolResponse.ToolSuccess success -> executionCall.withResult(success.output(), true);
                        case ToolResponse.ToolFailure failure -> executionCall.withResult(failure.output(), false);
                        case ToolResponse.ToolPending pending -> executionCall.withResult(pending.output(), false);
                    };
            Optional<String> attemptId = (reconciler != null && workspaceId != null)
                    ? reconciler.findAttemptId(workspaceId, call.operationId())
                    : Optional.empty();
            return new ChildOutcome.Completed(completedCall, attemptId);
        } catch (Exception e) {
            return new ChildOutcome.Completed(
                    executionCall.withResult(
                            "Tool execution failed on resume: " + AgentErrorMessageUtil.safeMessage(e), false),
                    Optional.empty());
        }
    }

    private sealed interface ChildOutcome {
        record Completed(ParallelToolCall call, Optional<String> attemptId) implements ChildOutcome {}

        record Pause(ApprovalFrame frame) implements ChildOutcome {}

        record Blocked() implements ChildOutcome {}
    }

    private AgentResult completeBatch(
            AgentCheckpoint checkpoint, List<ReasoningStep> history, List<ParallelToolCall> completed) {
        try {
            String completedJson = objectMapper.writeValueAsString(completed);
            String summary = completed.stream()
                    .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
                    .collect(Collectors.joining(", "));
            boolean allSuccess = completed.stream().allMatch(ParallelToolCall::success);
            OptionalInt pendingIdx = findPendingParallelStepIndex(history);
            ReasoningStep pendingStep = pendingIdx.isPresent() ? history.get(pendingIdx.getAsInt()) : null;
            String thought = pendingStep != null && pendingStep.thought() != null
                    ? pendingStep.thought()
                    : "Human Approved (batch)";
            int inTk = pendingStep != null ? pendingStep.inputTokens() : 0;
            int outTk = pendingStep != null ? pendingStep.outputTokens() : 0;
            ReasoningStep aggregateStep = ReasoningStep.parallelBatch(
                            thought, Map.of(ReasoningStep.PARALLEL_BATCH_ACTION_INPUT_KEY, completedJson), inTk, outTk)
                    .withObservation(summary, allSuccess);
            if (pendingIdx.isPresent()) {
                history.set(pendingIdx.getAsInt(), aggregateStep);
            } else {
                history.add(aggregateStep);
            }
            completeInProgressCheckpoint(checkpoint);
            return null;
        } catch (Exception e) {
            return handleCorruptBatch(checkpoint, history);
        }
    }

    private OptionalInt findPendingParallelStepIndex(List<ReasoningStep> history) {
        if (history == null) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < history.size(); i++) {
            ReasoningStep step = history.get(i);
            if (step.isParallelBatch() && step.observation() == null) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    private ToolInvocationGuard.GuardDecision evaluateGuard(
            AgentExecutionContext context,
            String effectiveRole,
            String toolName,
            Map<String, Object> params,
            List<ReasoningStep> history) {
        if (guard == null) {
            log.errorf(
                    "[ResumeStrategy] Mandatory tool guard unavailable; blocking tool %s in session %s",
                    toolName, context.sessionId());
            return new ToolInvocationGuard.GuardDecision.Blocked(
                    "Tool execution blocked because safety guard unavailable.");
        }
        return guard.evaluate(
                context.sessionId(),
                effectiveRole,
                context.autonomyLevel(),
                toolName,
                params,
                history,
                context.goal(),
                List.of());
    }

    private Optional<AgentResult> handleDelegatedPause(
            AgentCheckpoint checkpoint,
            List<ReasoningStep> history,
            String sessionId,
            List<ParallelToolCall> completed) {
        Optional<DelegatedPause> delegatedPause = findDelegatedPause(completed);
        if (delegatedPause.isEmpty()) {
            return Optional.empty();
        }
        checkpoint.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        agentStore.save(checkpoint);
        DelegatedPause pause = delegatedPause.get();
        return Optional.of(AgentResult.awaitingApproval(
                new ApprovalFrame(pause.toolName(), pause.params(), RiskLevel.HIGH), sessionId, List.copyOf(history)));
    }

    private Optional<DelegatedPause> findDelegatedPause(List<ParallelToolCall> completed) {
        for (ParallelToolCall call : completed) {
            if (!call.success()
                    || call.observation() == null
                    || !call.observation().contains(UserMessages.DELEGATION_PAUSED_MARKER)) {
                continue;
            }
            String toolName = UserMessages.extractPausedTool(call.observation());
            String paramsJson = UserMessages.extractPausedParamsJson(call.observation());
            if (toolName == null || toolName.isBlank() || paramsJson == null) {
                continue;
            }
            if (!BatchDecisionUtil.isValidBatchParamsJson(paramsJson, objectMapper)) {
                log.warnf("[ResumeStrategy] Invalid delegated pause payload for %s", toolName);
                continue;
            }
            Map<String, Object> params = BatchDecisionUtil.parseBatchDisplayParams(paramsJson, objectMapper);
            return Optional.of(new DelegatedPause(toolName, params));
        }
        return Optional.empty();
    }

    private record DelegatedPause(String toolName, Map<String, Object> params) {}

    private String buildDelegationParams(String toolName, String paramsJson, String owner) {
        Map<String, Object> params = Map.of();
        if (paramsJson != null && !paramsJson.isBlank()) {
            try {
                params = objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warnf(
                        "[ResumeStrategy] Could not parse batch parameters for %s: %s",
                        toolName, LogUtil.rootMessage(e));
            }
        }
        try {
            return objectMapper.writeValueAsString(
                    Map.of("agent", owner, "goal", ToolStepResolutionUtils.buildDelegateGoal(toolName, params)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build batch delegation request for " + toolName, e);
        }
    }

    private AgentResult handleCorruptBatch(AgentCheckpoint cp, List<ReasoningStep> history) {
        cp.setStatus(CheckpointStatus.FAILED);
        agentStore.save(cp);
        return AgentResult.failed(
                "Failed to finalize parallel tool batch.",
                AgentResult.FailureCategory.SYSTEM_ERROR,
                List.copyOf(history));
    }

    private void completeInProgressCheckpoint(AgentCheckpoint checkpoint) {
        checkpoint.setPendingToolName(null);
        checkpoint.setPendingToolParametersJson(null);
        checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
        agentStore.save(checkpoint);
    }
}
