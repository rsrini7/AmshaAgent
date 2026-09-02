package com.amsha.core.agent.hitl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.CheckpointResolutionException;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.sse.HitlEventType;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

    @Inject
    public CheckpointApprovalService(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ObjectMapper objectMapper,
            Instance<CheckpointResumeHandler> resumeHandlerInstance,
            TracePublisher tracePublisher) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.objectMapper = objectMapper;
        this.resumeHandlerInstance = resumeHandlerInstance;
        this.tracePublisher = tracePublisher;
    }

    /** Visible for tests that do not require trace publishing. */
    public CheckpointApprovalService(
            StatefulAgentStore agentStore,
            AgentSseService agentSseService,
            ObjectMapper objectMapper,
            Instance<CheckpointResumeHandler> resumeHandlerInstance) {
        this(agentStore, agentSseService, objectMapper, resumeHandlerInstance, null);
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
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-APPROVE-ALL");
        int updated = applyAllPending(cp, decisions, ToolDecision.APPROVED);
        if (updated == 0) throw alreadyDecided(sessionId, "all");

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
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
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
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-APPROVE-TOOL");
        if (!applyDecision(cp, decisions, toolName, ToolDecision.APPROVED)) {
            throw alreadyDecided(sessionId, toolName);
        }
        setLocalDecisions(cp, decisions, "HITL-APPROVE-TOOL");
        publishDecision(cp, HitlEventType.APPROVED, null, toolName);
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint denyTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        Map<String, ToolDecision> decisions = readDecisions(cp, "HITL-DENY-TOOL");
        if (!applyDecision(cp, decisions, toolName, ToolDecision.DENIED)) {
            throw alreadyDecided(sessionId, toolName);
        }
        setLocalDecisions(cp, decisions, "HITL-DENY-TOOL");
        publishDecision(cp, HitlEventType.DENIED, reason, toolName);
        return cp;
    }

    private Map<String, ToolDecision> readDecisions(AgentCheckpoint cp, String operation) {
        String json = cp.getBatchToolDecisions();
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
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
        if (!isFinalDecision(target) || toolName == null || !ToolDecision.PENDING.equals(decisions.get(toolName))) {
            return false;
        }
        boolean updated =
                agentStore.updateBatchToolDecision(cp.getSessionId(), cp.getAgentRole(), toolName, target.name());
        if (updated) decisions.put(toolName, target);
        return updated;
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
    public void triggerResume(AgentCheckpoint cp) {
        if (cp == null) return;

        AgentCheckpoint latest =
                agentStore.findLatestAwaitingApproval(cp.getSessionId()).orElse(cp);
        if (BatchDecisionUtil.hasPendingToolNames(latest, objectMapper)) {
            log.debugf(
                    "HITL-RESUME: skipping trigger for session %s as batch tools are still pending",
                    latest.getSessionId());
            return;
        }

        log.infof(
                "HITL-RESUME: triggering resume for session %s role %s", latest.getSessionId(), latest.getAgentRole());

        try {
            if (resumeHandlerInstance.isResolvable()) {
                CheckpointResumeHandler handler = resumeHandlerInstance.get();
                handler.resumeAfterBatchDecision(
                        latest.getSessionId(),
                        latest.getAgentRole(),
                        latest.getChannelPlatformId() != null ? latest.getChannelPlatformId() : "unknown",
                        latest.getChannelPlatformId() != null ? latest.getChannelPlatformId() : latest.getSessionId(),
                        latest.getChannelType() != null ? latest.getChannelType() : "websocket");
            }
        } catch (Exception e) {
            log.warnf(
                    "HITL-RESUME: failed to trigger CheckpointResumeHandler automatically: %s", LogUtil.rootMessage(e));
        }

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
    }
}
