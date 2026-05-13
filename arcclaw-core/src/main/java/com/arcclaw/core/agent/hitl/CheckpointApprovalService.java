package com.arcclaw.core.agent.hitl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.agent.loop.CheckpointResolutionException;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.agent.sse.HitlEventType;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
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

    @Inject
    public CheckpointApprovalService(StatefulAgentStore agentStore,
                                    AgentSseService agentSseService,
                                    ObjectMapper objectMapper,
                                    Instance<CheckpointResumeHandler> resumeHandlerInstance) {
        this.agentStore = agentStore;
        this.agentSseService = agentSseService;
        this.objectMapper = objectMapper;
        this.resumeHandlerInstance = resumeHandlerInstance;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void approve(String sessionId, String agentRole, Map<String, ToolDecision> decisions) {
        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, agentRole);
        if (cpOpt.isEmpty()) {
            log.warnf("HITL-APPROVE: checkpoint not found for session %s role %s", sessionId, agentRole);
            return;
        }

        AgentCheckpoint cp = cpOpt.get();
        // §Bug-1 Fix: Do NOT set status = APPROVED here to avoid SQLite constraint failures.
        // The triggerResume call below will transition it to RESUMING.
        try {
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
        } catch (JsonProcessingException e) {
            log.errorf("HITL-APPROVE: failed to serialize decisions: %s", ExceptionUtils.getRootCauseMessage(e));
        }
        agentStore.save(cp);

        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                sessionId, HitlEventType.APPROVED.wireValue(), cp.getPendingToolName(), cp.getPendingToolParametersJson(), null, null, Instant.now()
        ));
        log.infof("HITL-APPROVE: session %s role %s approved with %d decisions", sessionId, agentRole, decisions.size());
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint approveAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        // §Bug-1 Fix: Keep status as AWAITING_APPROVAL.
        
        try {
            String decisionsJson = cp.getBatchToolDecisions();
            if (decisionsJson != null && !decisionsJson.isBlank()) {
                Map<String, ToolDecision> decisions = objectMapper.readValue(decisionsJson, new TypeReference<>() {});
                decisions.replaceAll((k, v) -> ToolDecision.APPROVED);
                cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
            }
        } catch (JsonProcessingException e) {
            log.errorf("HITL-APPROVE-ALL: failed to update decisions: %s", ExceptionUtils.getRootCauseMessage(e));
        }

        agentStore.save(cp);
        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                sessionId, HitlEventType.APPROVED.wireValue(), cp.getPendingToolName(), cp.getPendingToolParametersJson(), null, null, Instant.now()
        ));
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void deny(String sessionId, String agentRole, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, agentRole);
        if (cpOpt.isEmpty()) return;

        AgentCheckpoint cp = cpOpt.get();
        cp.setStatus(CheckpointStatus.DENIED);
        agentStore.save(cp);

        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                sessionId, HitlEventType.DENIED.wireValue(), cp.getPendingToolName(), cp.getPendingToolParametersJson(), null, reason, Instant.now()
        ));
        log.infof("HITL-DENY: session %s role %s denied. reason=%s", sessionId, agentRole, reason);
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint denyAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        // §Bug-1 Fix: Keep status as AWAITING_APPROVAL so claimForResume CAS can succeed.
        // The resume handler will manage the final status transition.

        try {
            String decisionsJson = cp.getBatchToolDecisions();
            if (decisionsJson != null && !decisionsJson.isBlank()) {
                Map<String, ToolDecision> decisions = objectMapper.readValue(decisionsJson, new TypeReference<>() {});
                decisions.replaceAll((k, v) -> ToolDecision.DENIED);
                cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));
            }
        } catch (JsonProcessingException e) {
            log.errorf("HITL-DENY-ALL: failed to update decisions: %s", ExceptionUtils.getRootCauseMessage(e));
        }

        agentStore.save(cp);
        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                sessionId, HitlEventType.DENIED.wireValue(), cp.getPendingToolName(), cp.getPendingToolParametersJson(), null, reason, Instant.now()
        ));
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint approveTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        // §Bug-2 Fix: Explicitly use the passed-in toolName parameter.
        try {
            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            String existingDecisions = cp.getBatchToolDecisions();
            if (existingDecisions != null && !existingDecisions.isBlank()) {
                decisions = objectMapper.readValue(existingDecisions, new TypeReference<>() {});
            }
            
            decisions.put(toolName, ToolDecision.APPROVED);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));

            // §Bug-1 Fix: Keep as AWAITING_APPROVAL.
            agentStore.save(cp);

            agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                    sessionId, HitlEventType.APPROVED.wireValue(), toolName, null, null, null, Instant.now()
            ));
        } catch (JsonProcessingException e) {
            log.errorf("HITL-APPROVE-TOOL: failed to update decision for %s: %s", toolName, ExceptionUtils.getRootCauseMessage(e));
        }
        return cp;
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public AgentCheckpoint denyTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) throw new CheckpointResolutionException("No pending checkpoint found for session: " + sessionId, 404);

        AgentCheckpoint cp = cpOpt.get();
        // §Bug-2 Fix: Explicitly use the passed-in toolName parameter.
        try {
            Map<String, ToolDecision> decisions = new LinkedHashMap<>();
            String existingDecisions = cp.getBatchToolDecisions();
            if (existingDecisions != null && !existingDecisions.isBlank()) {
                decisions = objectMapper.readValue(existingDecisions, new TypeReference<>() {});
            }
            
            decisions.put(toolName, ToolDecision.DENIED);
            cp.setBatchToolDecisions(objectMapper.writeValueAsString(decisions));

            // §Bug-1 Fix: Keep as AWAITING_APPROVAL.
            agentStore.save(cp);

            agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                    sessionId, HitlEventType.DENIED.wireValue(), toolName, null, null, reason, Instant.now()
            ));
        } catch (JsonProcessingException e) {
            log.errorf("HITL-DENY-TOOL: failed to update decision for %s: %s", toolName, ExceptionUtils.getRootCauseMessage(e));
        }
        return cp;
    }

    /**
     * Triggers resume after a batch decision. Publishes a single RESUMED SSE
     * event only after the resume handler completes (or fails).
     * <p>
     * §Issue-2 Fix: Previously this published RESUMED before calling the handler,
     * and {@link #recordResumed} would publish a second one. Now we publish exactly once.
     */
    public void triggerResume(AgentCheckpoint cp) {
        if (cp == null) return;

        if (!BatchDecisionUtil.pendingToolNames(cp, objectMapper).isEmpty()) {
            log.debugf("HITL-RESUME: skipping trigger for session %s as batch tools are still pending", cp.getSessionId());
            return;
        }

        log.infof("HITL-RESUME: triggering resume for session %s role %s", cp.getSessionId(), cp.getAgentRole());

        // §Resume-Fix: actively drive resumption to prevent the HITL flow from stalling
        try {
            if (resumeHandlerInstance.isResolvable()) {
                CheckpointResumeHandler handler = resumeHandlerInstance.get();
                handler.resumeAfterBatchDecision(
                    cp.getSessionId(), cp.getAgentRole(),
                    cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : "unknown",
                    cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : cp.getSessionId(),
                    cp.getChannelType() != null ? cp.getChannelType() : "websocket"
                );
            }
        } catch (Exception e) {
            log.warnf("HITL-RESUME: failed to trigger CheckpointResumeHandler automatically: %s", ExceptionUtils.getRootCauseMessage(e));
        }

        // Single RESUMED event published after the handler completes (or fails)
        agentSseService.publish(cp.getSessionId(), new AgentSseService.HitlEvent(
                cp.getSessionId(), HitlEventType.RESUMED.wireValue(), null, null, null, null, Instant.now()
        ));
    }

    /**
     * @deprecated No longer needed — {@link #triggerResume} publishes the single RESUMED event.
     *             Retained for backward compatibility with callers that haven't been updated yet.
     */
    @Deprecated(since = "P4")
    public void recordResumed(String sessionId, String agentRole) {
        // No-op: triggerResume now publishes the single RESUMED event.
        log.debugf("HITL-RESUME: recordResumed called for session %s (no-op, triggerResume handles SSE)", sessionId);
    }
}
