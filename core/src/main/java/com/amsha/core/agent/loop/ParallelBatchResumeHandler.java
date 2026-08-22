package com.amsha.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.ToolResponse;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

class ParallelBatchResumeHandler {

    private static final Logger log = Logger.getLogger(ParallelBatchResumeHandler.class);

    private final StatefulAgentStore agentStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ToolDelegationResolver delegationResolver;
    private final ToolInvocationGuard guard;

    ParallelBatchResumeHandler(
            StatefulAgentStore agentStore,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            ToolDelegationResolver delegationResolver,
            ToolInvocationGuard guard) {
        this.agentStore = agentStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.delegationResolver = delegationResolver;
        this.guard = guard;
    }

    AgentResult resumeParallelBatch(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            AgentCheckpoint checkpoint,
            String channelType) {

        String sessionId = context.sessionId();
        log.infof("[ResumeStrategy] Resuming parallel batch for session %s", sessionId);

        List<ParallelToolCall> calls;
        try {
            calls = objectMapper.readValue(
                    checkpoint.getPendingToolParametersJson(), new TypeReference<List<ParallelToolCall>>() {});
        } catch (Exception e) {
            log.errorf("[ResumeStrategy] session=%s: failed to parse batch", checkpoint.getSessionId());
            return handleCorruptBatch(checkpoint, history);
        }

        Map<String, ToolDecision> decisions = Map.of();
        if (checkpoint.hasBatchToolDecisions()) {
            try {
                decisions = objectMapper.readValue(
                        checkpoint.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
            } catch (Exception e) {
                log.debugf("Batch decision parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }

        Map<String, String> preExecuted = Map.of();
        if (checkpoint.hasPreExecutedResults()) {
            try {
                preExecuted = objectMapper.readValue(
                        checkpoint.getPreExecutedResults(), new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.debugf("Pre-executed result parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }

        List<ParallelToolCall> completed = new ArrayList<>();
        for (ParallelToolCall call : calls) {
            if (preExecuted.containsKey(call.toolName())) {
                try {
                    ToolResponse stored = objectMapper.readValue(preExecuted.get(call.toolName()), ToolResponse.class);
                    completed.add(
                            switch (stored) {
                                case ToolResponse.ToolSuccess success -> call.withResult(success.output(), true);
                                case ToolResponse.ToolFailure failure -> call.withResult(failure.output(), false);
                                case ToolResponse.ToolPending pending -> call.withResult(pending.output(), false);
                            });
                    continue;
                } catch (Exception e) {
                    log.debugf("Pre-executed tool response parse fallback (non-fatal): %s", LogUtil.rootMessage(e));
                }
            }

            if (decisions.getOrDefault(call.toolName(), ToolDecision.PENDING) == ToolDecision.DENIED) {
                completed.add(call.withResult("User denied this tool execution.", false));
                continue;
            }

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
                if (!invocationToolName.equals(call.toolName())
                        || !Objects.equals(invocationParamsJson, call.paramsJson())) {
                    executionCall = new ParallelToolCall(
                            call.callId(), invocationToolName, Map.of(), invocationParamsJson, null, false);
                }

                var parsedParams = invocationParamsJson != null && !invocationParamsJson.isBlank()
                        ? objectMapper.readValue(invocationParamsJson, new TypeReference<Map<String, Object>>() {})
                        : Map.<String, Object>of();
                executionCall = new ParallelToolCall(
                        call.callId(), invocationToolName, parsedParams, invocationParamsJson, null, false);

                ToolInvocationGuard.GuardDecision decision = guard != null
                        ? guard.evaluate(
                                context.sessionId(),
                                effectiveRole,
                                context.autonomyLevel(),
                                invocationToolName,
                                parsedParams,
                                history,
                                context.goal(),
                                List.of())
                        : new ToolInvocationGuard.GuardDecision.Allow();

                if (decision instanceof ToolInvocationGuard.GuardDecision.Blocked b) {
                    completed.add(executionCall.withResult(
                            "Tool execution blocked by safety guard: " + b.observation(), false));
                    continue;
                }

                ToolResponse response = WorkspaceContext.runWithFullAutonomy(
                        context.sessionId(),
                        context.userId(),
                        context.chatId(),
                        effectiveRole,
                        channelType,
                        context.channelPlatformId(),
                        () -> toolRegistry.invokeTool(invocationToolName, invocationParamsJson, context.sessionId()));

                completed.add(
                        switch (response) {
                            case ToolResponse.ToolSuccess success -> executionCall.withResult(success.output(), true);
                            case ToolResponse.ToolFailure failure -> executionCall.withResult(failure.output(), false);
                            case ToolResponse.ToolPending pending -> executionCall.withResult(pending.output(), false);
                        });
            } catch (Exception e) {
                completed.add(executionCall.withResult(
                        "Tool execution failed on resume: " + AgentErrorMessageUtil.safeMessage(e), false));
            }
        }

        try {
            String completedJson = objectMapper.writeValueAsString(completed);
            String summary = completed.stream()
                    .map(c -> c.toolName() + "→" + (c.success() ? "OK" : "FAIL"))
                    .collect(Collectors.joining(", "));
            history.add(ReasoningStep.parallelBatch("Human Approved (batch)", Map.of("batch", completedJson), 0, 0)
                    .withObservation(summary));
            completeInProgressCheckpoint(checkpoint);
            return null;
        } catch (Exception e) {
            return handleCorruptBatch(checkpoint, history);
        }
    }

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
