package com.amsha.core.agent.hitl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.agent.loop.CheckpointResolutionException;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.sse.HitlEventType;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles the logic for approving or denying a pending checkpoint.
 * Centralizes state transitions and SSE notifications for HITL.
 *
 * <p><strong>§5.9 Architecture Note:</strong> This class lives in
 * {@code .agent.hitl} (NOT {@code .agent.loop}) specifically because
 * it requires {@code @Transactional} methods. The arch rules forbid
 * {@code @Transactional} annotations inside {@code .agent.loop}.
 */
@ApplicationScoped
public class CheckpointApprovalService {

    private static final Logger log = Logger.getLogger(CheckpointApprovalService.class);

    private final StatefulAgentStore agentStore;
    private final AgentSseService agentSseService;
    private final ObjectMapper objectMapper;
    private final Instance<CheckpointResumeHandler> resumeHandlerInstance;
    private final TracePublisher tracePublisher;
    private final Instance<DomainEventBus> eventBusInstance;

    @Inject
    ApprovalLeaseStore approvalLeaseStore;

    @Inject
    public CheckpointApprovalService(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ObjectMapper objectMapper,
            Instance<CheckpointResumeHandler> resumeHandlerInstance,
            TracePublisher tracePublisher,
            Instance<DomainEventBus> eventBusInstance) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.objectMapper = objectMapper;
        this.resumeHandlerInstance = resumeHandlerInstance;
        this.tracePublisher = tracePublisher;
        this.eventBusInstance = eventBusInstance;
    }

    /** Visible for tests that do not require trace publishing or event bus. */
    public CheckpointApprovalService(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ObjectMapper objectMapper,
            Instance<CheckpointResumeHandler> resumeHandlerInstance) {
        this(agentStore, agentSseService, objectMapper, resumeHandlerInstance, null, null);
    }

    /** Visible for tests that do not require event bus. */
    public CheckpointApprovalService(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ObjectMapper objectMapper,
            Instance<CheckpointResumeHandler> resumeHandlerInstance,
            TracePublisher tracePublisher) {
        this(agentStore, agentSseService, objectMapper, resumeHandlerInstance, tracePublisher, null);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void approve(String sessionId, String agentRole, Map<String, ToolDecision> decisions) {
        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, agentRole);
        if (cpOpt.isEmpty()) {
            log.warnf("HITL-APPROVE: checkpoint not found for session %s role %s", sessionId, agentRole);
            return;
        }

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> current = readDecisions(cp, "HITL-APPROVE");
        int updated = 0;
        if (decisions != null) {
            for (Map.Entry<String, ToolDecision> entry : decisions.entrySet()) {
                if (isFinalDecision(entry.getValue()) && applyDecision(cp, current, entry.getKey(), entry.getValue())) {
                    updated++;
                }
            }
        }
        if (updated == 0) return;

        setLocalDecisions(cp, current, "HITL-APPROVE");
        publishDecision(cp, HitlEventType.APPROVED, null, null);
        log.infof("HITL-APPROVE: session %s role %s updated %d decisions", sessionId, agentRole, updated);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint approveAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore
                .findLatestAwaitingApproval(sessionId)
                .or(() -> agentStore.findLatestAwaitingApprovalBySessionContaining(sessionId));
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-APPROVE-ALL");
        int updated = applyAllPending(cp, decisions, ToolDecision.APPROVED);
        if (updated == 0) {
            // A double-click or retry can arrive after another request has
            // already approved every call while the checkpoint is still marked
            // AWAITING_APPROVAL. Treat that state as an idempotent success.
            if (decisions.values().stream().allMatch(ToolDecision.APPROVED::equals)) {
                return cp;
            }
            throw alreadyDecided(sessionId, "all");
        }

        setLocalDecisions(cp, decisions, "HITL-APPROVE-ALL");
        publishDecision(cp, HitlEventType.APPROVED, null, null);
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void deny(String sessionId, String agentRole, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, agentRole);
        if (cpOpt.isEmpty()) return;

        AgentCheckpoint cp = cpOpt.get();
        if (cp.hasBatchToolDecisions()) {
            denyAll(sessionId, reason);
            return;
        }
        if (cp.getStatus() != CheckpointStatus.AWAITING_APPROVAL) return;
        cp.setStatus(CheckpointStatus.DENIED);
        agentStore.save(cp);

        publishDecision(cp, HitlEventType.DENIED, reason, null);
        log.infof("HITL-DENY: session %s role %s denied. reason=%s", sessionId, agentRole, reason);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint denyAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore
                .findLatestAwaitingApproval(sessionId)
                .or(() -> agentStore.findLatestAwaitingApprovalBySessionContaining(sessionId));
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-DENY-ALL");
        int updated = applyAllPending(cp, decisions, ToolDecision.DENIED);
        if (updated == 0) throw alreadyDecided(sessionId, "all");

        setLocalDecisions(cp, decisions, "HITL-DENY-ALL");
        publishDecision(cp, HitlEventType.DENIED, reason, null);
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint approveTool(String sessionId, String toolName, String reason) {
        String cleanTool = BatchDecisionUtil.normalizeToolName(toolName);
        if (cleanTool == null || "all".equalsIgnoreCase(cleanTool)) {
            return approveAll(sessionId, reason);
        }
        Optional<AgentCheckpoint> cpOpt = agentStore
                .findLatestAwaitingApproval(sessionId)
                .or(() -> agentStore.findLatestAwaitingApprovalBySessionContaining(sessionId));
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-APPROVE-TOOL");
        if (!applyDecision(cp, decisions, cleanTool, ToolDecision.APPROVED)) {
            String decisionKey = resolveDecisionKey(cp, decisions, cleanTool);
            if (decisionKey != null && ToolDecision.APPROVED == decisions.get(decisionKey)) {
                return cp;
            }
            throw alreadyDecided(sessionId, cleanTool);
        }
        setLocalDecisions(cp, decisions, "HITL-APPROVE-TOOL");
        publishDecision(cp, HitlEventType.APPROVED, null, cleanTool);
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint denyTool(String sessionId, String toolName, String reason) {
        String cleanTool = BatchDecisionUtil.normalizeToolName(toolName);
        if (cleanTool == null || "all".equalsIgnoreCase(cleanTool)) {
            return denyAll(sessionId, reason);
        }
        Optional<AgentCheckpoint> cpOpt = agentStore
                .findLatestAwaitingApproval(sessionId)
                .or(() -> agentStore.findLatestAwaitingApprovalBySessionContaining(sessionId));
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-DENY-TOOL");
        if (!applyDecision(cp, decisions, cleanTool, ToolDecision.DENIED)) {
            String decisionKey = resolveDecisionKey(cp, decisions, cleanTool);
            if (decisionKey != null && ToolDecision.DENIED == decisions.get(decisionKey)) {
                return cp;
            }
            throw alreadyDecided(sessionId, cleanTool);
        }
        setLocalDecisions(cp, decisions, "HITL-DENY-TOOL");
        publishDecision(cp, HitlEventType.DENIED, reason, cleanTool);
        return cp;
    }

    private Map<String, ToolDecision> readDecisions(AgentCheckpoint cp, String operation) {
        String json = cp.getBatchToolDecisions();
        if (json != null && json.isBlank()) {
            throw new CheckpointResolutionException("Approval state cannot be resolved safely", 409);
        }
        if (json == null) {
            if (operation != null && operation.contains("ALL")) {
                throw new CheckpointResolutionException("Approval state cannot be resolved safely", 409);
            }
            if (cp.getPendingToolName() != null && !cp.getPendingToolName().isBlank()) {
                Map<String, ToolDecision> fallback = new LinkedHashMap<>();
                fallback.put(cp.getPendingToolName(), ToolDecision.PENDING);
                return fallback;
            }
            return new LinkedHashMap<>();
        }
        try {
            Map<String, ToolDecision> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException e) {
            log.errorf("%s: failed to read persisted decisions: %s", operation, LogUtil.rootMessage(e));
            throw new CheckpointResolutionException("Approval state cannot be resolved safely", 409);
        }
    }

    private int applyAllPending(AgentCheckpoint cp, Map<String, ToolDecision> decisions, ToolDecision target) {
        int updated = 0;
        for (String toolName : decisions.keySet().toArray(String[]::new)) {
            if (applyDecision(cp, decisions, toolName, target)) updated++;
        }
        return updated;
    }

    private boolean applyDecision(
            AgentCheckpoint cp, Map<String, ToolDecision> decisions, String toolName, ToolDecision target) {
        String cleanTool = BatchDecisionUtil.normalizeToolName(toolName);
        String decisionKey = resolveDecisionKey(cp, decisions, cleanTool);
        if (!isFinalDecision(target)
                || decisionKey == null
                || !ToolDecision.PENDING.equals(decisions.get(decisionKey))) {
            return false;
        }
        boolean updated =
                agentStore.updateBatchToolDecision(cp.getSessionId(), cp.getAgentRole(), decisionKey, target.name());
        if (updated) {
            decisions.put(decisionKey, target);
            if (target == ToolDecision.APPROVED) {
                grantLease(cp.getSessionId(), cp, decisionKey);
            }
        }
        return updated;
    }

    private String resolveDecisionKey(AgentCheckpoint cp, Map<String, ToolDecision> decisions, String requested) {
        if (requested == null || requested.isBlank() || "all".equalsIgnoreCase(requested)) return requested;
        if (decisions.containsKey(requested)) return requested;
        try {
            List<ParallelToolCall> calls = objectMapper.readValue(
                    cp.getPendingToolParametersJson(), new TypeReference<List<ParallelToolCall>>() {});
            List<String> matches = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                ParallelToolCall call = calls.get(i);
                if (requested.equalsIgnoreCase(call.toolName())) {
                    matches.add(BatchDecisionUtil.decisionKey(call, i + 1, calls));
                }
            }
            if (matches.size() > 1) {
                throw new CheckpointResolutionException(
                        "Tool name is ambiguous; approve the specific parallel call ID", 409);
            }
            return matches.isEmpty() ? requested : matches.getFirst();
        } catch (JsonProcessingException e) {
            return requested;
        }
    }

    private void grantLease(String sessionId, AgentCheckpoint cp, String toolName) {
        if (approvalLeaseStore == null || sessionId == null || cp == null || toolName == null) {
            return;
        }
        try {
            String paramsJson = cp.getPendingToolParametersJson();
            if (paramsJson == null || paramsJson.isBlank()) {
                return;
            }
            JsonNode root = objectMapper.readTree(paramsJson);
            if (root.isArray()) {
                for (JsonNode element : root) {
                    if (toolName.equals(element.path("toolName").asText(null))
                            || toolName.equals(element.path("callId").asText(null))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> extracted = objectMapper.convertValue(element.path("params"), Map.class);
                        if (extracted != null) {
                            approvalLeaseStore.grant(
                                    sessionId, element.path("toolName").asText(toolName), extracted);
                        }
                    }
                }
            } else if (root.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> extracted = objectMapper.convertValue(root, Map.class);
                if (extracted != null) {
                    approvalLeaseStore.grant(sessionId, toolName, extracted);
                }
            }
        } catch (Exception e) {
            log.warnf(
                    "[CAS] Lease grant skipped for session=%s tool=%s: %s",
                    sessionId, toolName, LogUtil.rootMessage(e));
        }
    }

    private void setLocalDecisions(AgentCheckpoint cp, Map<String, ToolDecision> decisions, String operation) {
        try {
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (JsonProcessingException e) {
            log.errorf("%s: failed to serialize local decision view: %s", operation, LogUtil.rootMessage(e));
            throw new CheckpointResolutionException("Approval state cannot be returned safely", 409);
        }
    }

    private void publishDecision(AgentCheckpoint cp, HitlEventType type, String reason, String toolName) {
        agentSseService.publish(
                cp.getSessionId(),
                new AgentSseService.HitlEvent(
                        cp.getSessionId(),
                        type.wireValue(),
                        toolName != null ? toolName : cp.getPendingToolName(),
                        cp.getPendingToolParametersJson(),
                        null,
                        reason,
                        Instant.now()));

        if (eventBusInstance != null && eventBusInstance.isResolvable()) {
            eventBusInstance
                    .get()
                    .publish(
                            EventTypeCatalog.AGENT_STATE_CHANGED,
                            new AgentStateChangedEvent(
                                    cp.getSessionId(),
                                    cp.getAgentRole(),
                                    type.wireValue().toLowerCase(Locale.ROOT),
                                    toolName != null ? toolName : cp.getPendingToolName()));
        }

        if (tracePublisher != null) {
            String resolvedToolName = toolName != null ? toolName : cp.getPendingToolName();
            tracePublisher.publish(TracePublisher.newEvent(cp.getSessionId(), TraceStage.HITL_RESOLVED)
                    .withStation(StationCode.STATION_05_TOOL_GUARD)
                    .withStatus(type.wireValue())
                    .withDetail(resolvedToolName));
        }
    }

    private static boolean isFinalDecision(ToolDecision decision) {
        return decision == ToolDecision.APPROVED || decision == ToolDecision.DENIED;
    }

    private static CheckpointResolutionException alreadyDecided(String sessionId, String toolName) {
        return new CheckpointResolutionException(
                "Approval decision already resolved for session " + sessionId + " tool " + toolName, 409);
    }

    /**
     * Triggers resume after a batch decision. Publishes a single RESUMED SSE
     * event only after the resume handler completes (or fails).
     *
     * <p>§Issue-2 Fix: Previously this published RESUMED before calling the handler,
     * and a duplicate RESUMED would be published. Now we publish exactly once.
     */
    public boolean triggerResume(AgentCheckpoint cp) {
        if (cp == null) return false;

        AgentCheckpoint latest = agentStore
                .findLatestAwaitingApproval(cp.getSessionId())
                .or(() -> agentStore.findLatestAwaitingApprovalBySessionContaining(cp.getSessionId()))
                .orElse(cp);
        if (BatchDecisionUtil.hasPendingToolNames(latest, objectMapper)) {
            log.debugf(
                    "HITL-RESUME: skipping trigger for session %s as batch tools are still pending",
                    latest.getSessionId());
            return false;
        }

        log.infof(
                "HITL-RESUME: triggering resume for session %s role %s", latest.getSessionId(), latest.getAgentRole());

        boolean accepted = false;
        try {
            if (resumeHandlerInstance.isResolvable()) {
                CheckpointResumeHandler handler = resumeHandlerInstance.get();
                String effectiveChannelType = latest.getChannelType();
                if ((effectiveChannelType == null || effectiveChannelType.isBlank())
                        && latest.getSessionId() != null
                        && latest.getSessionId().startsWith(AgentSentinels.CLI_SESSION_PREFIX)) {
                    effectiveChannelType = ChannelType.CLI.id();
                }
                accepted = handler.resumeAfterBatchDecision(
                        latest.getSessionId(),
                        latest.getAgentRole(),
                        latest.getChannelPlatformId() != null ? latest.getChannelPlatformId() : "unknown",
                        latest.getChannelPlatformId() != null ? latest.getChannelPlatformId() : latest.getSessionId(),
                        effectiveChannelType != null ? effectiveChannelType : "websocket");
            } else {
                log.warnf(
                        "HITL-RESUME: no CheckpointResumeHandler is resolvable for session %s", latest.getSessionId());
            }
        } catch (Exception e) {
            log.warnf(
                    "HITL-RESUME: failed to trigger CheckpointResumeHandler automatically: %s", LogUtil.rootMessage(e));
        }

        if (!accepted) return false;

        agentSseService.publish(
                latest.getSessionId(),
                new AgentSseService.HitlEvent(
                        latest.getSessionId(),
                        HitlEventType.RESUMED.wireValue(),
                        null,
                        null,
                        null,
                        null,
                        Instant.now()));

        if (eventBusInstance != null && eventBusInstance.isResolvable()) {
            eventBusInstance
                    .get()
                    .publish(
                            EventTypeCatalog.AGENT_STATE_CHANGED,
                            new AgentStateChangedEvent(latest.getSessionId(), latest.getAgentRole(), "resumed", ""));
        }
        return true;
    }
}
