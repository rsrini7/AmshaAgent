package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.sse.HitlEventType;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.state.SessionTree;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.llm.api.ToolCallJsonKeys;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles checkpoint persistence and SSE notification logic.
 */
@ApplicationScoped
@SuppressWarnings("java:S1192")
public class ApprovalCheckpointManager {

    private static final Logger log = Logger.getLogger(ApprovalCheckpointManager.class);

    private final StatefulAgentStore agentStore;
    private final ObjectMapper objectMapper;
    private final AgentSseService agentSseService;
    private final TracePublisher tracePublisher;
    private final Instance<DomainEventBus> eventBusInstance;

    @Inject
    public ApprovalCheckpointManager(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ObjectMapper objectMapper,
            TracePublisher tracePublisher,
            Instance<DomainEventBus> eventBusInstance) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.objectMapper = objectMapper;
        this.tracePublisher = tracePublisher;
        this.eventBusInstance = eventBusInstance;
    }

    /** Visible for tests that do not require trace publishing or event bus. */
    public ApprovalCheckpointManager(
            StatefulAgentStore agentStore, AgentSseService agentSseService, ObjectMapper objectMapper) {
        this(agentStore, agentSseService, objectMapper, null, null);
    }

    public void save(AgentCheckpoint cp) {
        agentStore.save(cp);
    }

    private AgentCheckpoint loadActiveOrCreate(String sessionId, String agentRole) {
        return agentStore
                .load(sessionId, agentRole)
                .filter(cp -> cp.getStatus() != CheckpointStatus.COMPLETED
                        && cp.getStatus() != CheckpointStatus.FAILED
                        && cp.getStatus() != CheckpointStatus.DENIED)
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpointRecord();
                    c.setSessionId(sessionId);
                    c.setAgentRole(agentRole);
                    return c;
                });
    }

    /**
     * Persists an AWAITING_APPROVAL checkpoint including channel info atomically.
     * Channel type and platformId MUST be stamped before save so resumeCheckpoint()
     * can read the correct Telegram/WebSocket target after approval.
     */
    public AgentResult persist(
            AgentExecutionContext context,
            ApprovalFrame frame,
            List<ReasoningStep> history,
            String channelType,
            String channelPlatformId) {
        String sessionId = context.sessionId();
        String agentRole = context.agentRole();
        String toolName = frame.tool();
        String paramsJson = "{}";
        try {
            paramsJson = objectMapper.writeValueAsString(frame.params());
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        agentStore.clearBatchResuming(sessionId);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolRole(agentRole);
        cp.setPendingToolParametersJson(paramsJson);

        try {
            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            decisions.put(toolName, ToolDecision.PENDING);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.errorf("Failed to serialize batch decisions for single tool %s: %s", toolName, LogUtil.rootMessage(e));
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(Instant.now());

        stampChannelInfo(cp, context, channelType, channelPlatformId);
        populateContextData(cp, context);

        // Save checkpoint metadata and reasoning steps in one atomic store write.
        agentStore.saveWithSteps(cp, history);

        log.infof(
                "STATE-MACHINE Pausing session %s for human approval of tool %s "
                        + "(channel=%s platformId=%s, risk=%s)",
                sessionId, toolName, channelType, channelPlatformId, frame.riskLevel());

        // SSE HitlEvent is always sent for UI/Web tracking (published to session and parent session tree)
        publishHitlEvent(
                sessionId,
                agentRole,
                new AgentSseService.HitlEvent(
                        sessionId,
                        HitlEventType.AWAITING_APPROVAL.wireValue(),
                        toolName,
                        paramsJson,
                        frame.riskLevel().name(),
                        null,
                        Instant.now()));

        publishHitlRequested(sessionId, toolName, frame.riskLevel().name());

        return AgentResult.awaitingApproval(frame, sessionId, List.copyOf(history));
    }

    /**
     * Keeps a sequential parent in progress while its delegated child owns
     * the pending approval. The child checkpoint remains the HITL source of
     * truth; the parent must not be left awaiting or stale, or it may relaunch
     * the already approved delegation.
     */
    public boolean persistDelegatedWait(
            AgentExecutionContext context,
            String delegationTool,
            Map<String, Object> params,
            List<ReasoningStep> history) {
        try {
            AgentCheckpoint checkpoint = loadActiveOrCreate(context.sessionId(), context.agentRole());
            agentStore.clearBatchResuming(context.sessionId());
            checkpoint.setStatus(CheckpointStatus.IN_PROGRESS);
            checkpoint.setPendingToolName(delegationTool);
            checkpoint.setPendingToolRole(context.agentRole());
            checkpoint.setPendingToolParametersJson(objectMapper.writeValueAsString(params));
            checkpoint.setPreExecutedResults(null);
            checkpoint.setBatchToolDecisions(
                    objectMapper.writeValueAsString(Map.of(delegationTool, ToolDecision.APPROVED)));
            checkpoint.setMaxIterations(context.maxIterations());
            checkpoint.setOriginalGoal(context.goal());
            checkpoint.setSystemNote(context.systemNote());
            checkpoint.setUpdatedAt(Instant.now());
            populateContextData(checkpoint, context);
            agentStore.saveWithSteps(checkpoint, history);
            return true;
        } catch (Exception e) {
            log.warnf(
                    "[DELEGATION] Could not persist parent delegated wait for %s: %s",
                    context.sessionId(), LogUtil.rootMessage(e));
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public AgentResult persist(
            AgentExecutionContext context,
            String toolName,
            String paramsJson,
            List<ReasoningStep> history,
            Object channelType,
            Object channelPlatformId) {
        Map<String, Object> params = Map.of();
        try {
            params = objectMapper.readValue(paramsJson, Map.class);
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }
        ApprovalFrame frame = new ApprovalFrame(toolName, params, RiskLevel.MEDIUM);
        return persist(
                context,
                frame,
                history,
                channelType != null ? channelType.toString() : null,
                channelPlatformId != null ? channelPlatformId.toString() : null);
    }

    /**
     * Specialized persistence for parallel tool batches.
     * Encapsulates the composite name generation and multi-tool status mapping.
     */
    public AgentResult persistBatch(
            AgentExecutionContext context,
            List<ParallelToolCall> calls,
            List<ReasoningStep> history,
            String channelType,
            String channelPlatformId) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        // 1. Generate composite name and JSON payload
        String compositeName = ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX
                + calls.stream()
                        .map(ParallelToolCall::toolName)
                        .distinct()
                        .reduce((a, b) -> a + "|" + b)
                        .orElse(ToolCallJsonKeys.BATCH);

        String originalBatchJson = "[]";
        Map<String, ToolDecision> decisions = new LinkedHashMap<>();
        try {
            originalBatchJson = objectMapper.writeValueAsString(calls);
            for (int i = 0; i < calls.size(); i++) {
                ParallelToolCall call = calls.get(i);
                decisions.put(decisionKey(call, i + 1, calls), ToolDecision.PENDING);
            }
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        // 2. Load or create checkpoint
        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        agentStore.clearBatchResuming(sessionId);

        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolRole(agentRole);
        cp.setPendingToolParametersJson(originalBatchJson);
        try {
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(Instant.now());

        stampChannelInfo(cp, context, channelType, channelPlatformId);
        populateContextData(cp, context);

        // 3. Save
        agentStore.saveWithSteps(cp, history);

        log.infof("STATE-MACHINE Pausing session %s for parallel batch approval: %s", sessionId, compositeName);

        // 4. Notify UI via SSE (published to session and parent session tree)
        publishHitlEvent(
                sessionId,
                agentRole,
                new AgentSseService.HitlEvent(
                        sessionId,
                        HitlEventType.AWAITING_APPROVAL.wireValue(),
                        compositeName,
                        originalBatchJson,
                        RiskLevel.MEDIUM.name(),
                        null,
                        Instant.now()));

        publishHitlRequested(sessionId, compositeName, RiskLevel.MEDIUM.name());

        // Create a synthetic frame for the result
        ApprovalFrame frame = new ApprovalFrame(compositeName, BatchDecisionUtil.toDisplayMap(calls), RiskLevel.MEDIUM);
        return AgentResult.awaitingApproval(frame, sessionId, List.copyOf(history));
    }

    /**
     * Specialized persistence for parallel batches where some tools already executed (pre-execution pass).
     * Preserves pre-executed results in the checkpoint so they aren't lost during the HITL pause. §7.30
     */
    public AgentResult persistBatchWithPreExecuted(
            AgentExecutionContext context,
            List<ParallelToolCall> allCalls,
            Map<String, ToolResponse> results,
            List<ReasoningStep> history,
            String channelType,
            String channelPlatformId) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        // §Issue-3 Fix: Build composite name from HITL-pending tools only (not pre-executed).
        // This prevents ResumeStrategyDispatcher from attempting to re-execute already-completed tools.
        String compositeName = ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX
                + allCalls.stream()
                        .filter(call -> !containsResult(results, call))
                        .map(ParallelToolCall::toolName)
                        .distinct()
                        .reduce((a, b) -> a + "|" + b)
                        .orElse(ToolCallJsonKeys.BATCH);

        String originalBatchJson = "[]";
        String preExecutedJson = ToolCallJsonKeys.EMPTY_JSON;
        Map<String, ToolDecision> decisions = new LinkedHashMap<>();

        try {
            originalBatchJson = objectMapper.writeValueAsString(allCalls);
            preExecutedJson = objectMapper
                    .writerFor(new TypeReference<Map<String, ToolResponse>>() {})
                    .writeValueAsString(results);
            for (int i = 0; i < allCalls.size(); i++) {
                ParallelToolCall call = allCalls.get(i);
                String key = decisionKey(call, i + 1, allCalls);
                if (!containsResult(results, call)) {
                    decisions.put(key, ToolDecision.PENDING);
                } else if (!decisions.containsKey(key)) {
                    decisions.put(key, ToolDecision.APPROVED);
                }
            }
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        AgentCheckpoint cp = loadActiveOrCreate(sessionId, agentRole);
        agentStore.clearBatchResuming(sessionId);

        boolean hasPending = decisions.values().stream().anyMatch(ToolDecision.PENDING::equals);
        cp.setStatus(hasPending ? CheckpointStatus.AWAITING_APPROVAL : CheckpointStatus.COMPLETED);
        cp.setPendingToolName(compositeName);
        cp.setPendingToolRole(agentRole);
        cp.setPendingToolParametersJson(originalBatchJson);
        cp.setPreExecutedResults(preExecutedJson);
        try {
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (Exception e) {
            log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
        }

        cp.setMaxIterations(context.maxIterations());
        cp.setOriginalGoal(context.goal());
        cp.setSystemNote(context.systemNote());
        cp.setUpdatedAt(Instant.now());

        stampChannelInfo(cp, context, channelType, channelPlatformId);
        populateContextData(cp, context);

        agentStore.saveWithSteps(cp, history);

        log.infof(
                "STATE-MACHINE Pausing session %s for parallel batch approval (with %d pre-executed): %s",
                sessionId, results.size(), compositeName);

        publishHitlEvent(
                sessionId,
                agentRole,
                new AgentSseService.HitlEvent(
                        sessionId,
                        HitlEventType.AWAITING_APPROVAL.wireValue(),
                        compositeName,
                        originalBatchJson,
                        RiskLevel.MEDIUM.name(),
                        null,
                        Instant.now()));

        publishHitlRequested(sessionId, compositeName, RiskLevel.MEDIUM.name());

        ApprovalFrame frame =
                new ApprovalFrame(compositeName, BatchDecisionUtil.toDisplayMap(allCalls), RiskLevel.MEDIUM);
        return AgentResult.awaitingApproval(frame, sessionId, List.copyOf(history));
    }

    private static String resultKey(ParallelToolCall call) {
        return call.callId() == null || call.callId().isBlank() ? call.toolName() : call.callId();
    }

    private static boolean containsResult(Map<String, ToolResponse> results, ParallelToolCall call) {
        return results.containsKey(resultKey(call)) || results.containsKey(call.toolName());
    }

    static String decisionKey(ParallelToolCall call, int index, List<ParallelToolCall> all) {
        return BatchDecisionUtil.decisionKey(call, index, all);
    }

    private void populateContextData(AgentCheckpoint cp, AgentExecutionContext context) {
        Map<String, Object> contextData = new LinkedHashMap<>();
        if (context.data() != null) {
            contextData.putAll(context.data());
        }
        if (context.userId() != null) {
            contextData.put("userId", context.userId());
        }
        if (context.chatId() != null) {
            contextData.put("chatId", context.chatId());
        }
        if (context.mediaAttachments() != null && !context.mediaAttachments().isEmpty()) {
            boolean hasAudio =
                    context.mediaAttachments().stream().anyMatch(a -> a != null && a.type() == AttachmentType.AUDIO);
            if (hasAudio) {
                contextData.put(TurnDispatchService.DATA_INBOUND_AUDIO, true);
            }
        }
        if (!contextData.isEmpty()) {
            try {
                cp.setContextDataJson(objectMapper.writeValueAsString(contextData));
            } catch (Exception e) {
                log.debugf("Serialization fallback for context data (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }
    }

    /**
     * Emits a {@code hitl_requested} trace when a checkpoint is persisted awaiting approval.
     * Best-effort: a missing publisher (e.g. lightweight unit test) is skipped.
     */
    private void publishHitlRequested(String sessionId, String toolName, String riskLevel) {
        if (tracePublisher == null) {
            return;
        }
        tracePublisher.publish(TracePublisher.newEvent(sessionId, TraceStage.HITL_REQUESTED)
                .withStation(StationCode.STATION_05_TOOL_GUARD)
                .withStatus("HITL_REQUIRED")
                .withDetail(toolName)
                .withRiskLevel(riskLevel));
    }

    private void stampChannelInfo(
            AgentCheckpoint cp, AgentExecutionContext context, String channelType, String channelPlatformId) {
        String type = (context != null
                        && context.channelType() != null
                        && !context.channelType().isBlank())
                ? context.channelType()
                : channelType;
        String platformId = (context != null
                        && context.channelPlatformId() != null
                        && !context.channelPlatformId().isBlank())
                ? context.channelPlatformId()
                : channelPlatformId;

        if (type == null
                && cp.getSessionId() != null
                && cp.getSessionId().startsWith(AgentSentinels.CLI_SESSION_PREFIX)) {
            type = ChannelType.CLI.id();
        }
        if (platformId == null && ChannelType.CLI.id().equalsIgnoreCase(type)) {
            platformId = "cli";
        }

        if (type != null && !type.isBlank()) {
            cp.setChannelType(type);
        }
        if (platformId != null && !platformId.isBlank()) {
            cp.setChannelPlatformId(platformId);
        }
    }

    private void publishHitlEvent(String sessionId, String agentRole, AgentSseService.HitlEvent event) {
        agentSseService.publish(sessionId, event);
        SessionTree.parentOf(sessionId).ifPresent(parent -> agentSseService.publish(parent, event));
        if (eventBusInstance != null && eventBusInstance.isResolvable()) {
            String role = (agentRole != null && !agentRole.isBlank()) ? agentRole : "coordinator";
            eventBusInstance
                    .get()
                    .publish(
                            EventTypeCatalog.AGENT_STATE_CHANGED,
                            new AgentStateChangedEvent(sessionId, role, "waiting-approval", event.toolName()));
        }
    }
}
