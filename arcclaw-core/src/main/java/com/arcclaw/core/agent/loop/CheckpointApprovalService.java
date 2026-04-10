package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CheckpointApprovalService {

    private static final Logger log = Logger.getLogger(CheckpointApprovalService.class);

    @Inject StatefulAgentStore agentStore;
    @Inject CheckpointResumeHandler resumeHandler;
    @Inject ObjectMapper objectMapper;
    @Inject AgentSseService agentSseService;

    // ✅ FIX: NOT_SUPPORTED instead of @Transactional (REQUIRED)
    // doUpdateBatchToolDecision is @Transactional(REQUIRES_NEW) and manages
    // its own connection. An outer REQUIRED tx on Connection-1 holds a reserved
    // SQLite write lock (XA BEGIN IMMEDIATE) that blocks the inner COMMIT.
    // NOT_SUPPORTED ensures no outer XA transaction is active during the write.
    // Reads (findLatestAwaitingApproval, isAllBatchToolsDecided) work fine
    // without an outer tx — SQLite auto-commits SELECT statements.
    @Transactional(TxType.NOT_SUPPORTED)   // was @Transactional
    public AgentCheckpoint approveTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, AgentSentinels.TOOL_DECISION_APPROVED);
        log.infof("Approved tool %s for session %s reason %s", toolName, sessionId, reason);
        agentSseService.publish(sessionId,
            new AgentSseService.HitlEvent(sessionId, AgentSentinels.TOOL_DECISION_APPROVED, toolName, null, reason, Instant.now()));
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        return allDecided ? cp : null;
    }

    @Transactional(TxType.NOT_SUPPORTED)   // was @Transactional
    public AgentCheckpoint denyTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, AgentSentinels.TOOL_DECISION_DENIED);
        log.infof("Denied tool %s for session %s reason %s", toolName, sessionId, reason);
        agentSseService.publish(sessionId,
            new AgentSseService.HitlEvent(sessionId, AgentSentinels.TOOL_DECISION_DENIED, toolName, null, reason, Instant.now()));
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        return allDecided ? cp : null;
    }

    @Transactional(TxType.NOT_SUPPORTED)   // was @Transactional
    public AgentCheckpoint approveAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        AgentCheckpoint cp = cpOpt.get();
        if (cp.getBatchToolDecisions() == null) {
            log.warnf("No batch tool decisions found for session %s, cannot approveAll", sessionId);
            return null;
        }
        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        if (pendingTools != null) {
            for (String pending : pendingTools) {
                if (pending != null) {
                    agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, AgentSentinels.TOOL_DECISION_APPROVED);
                    agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                        sessionId, AgentSentinels.TOOL_DECISION_APPROVED, pending, null, reason, Instant.now()));
                }
            }
        }
        log.infof("Approved ALL tools for session %s reason %s", sessionId, reason);
        return cp;
    }

    @Transactional(TxType.NOT_SUPPORTED)   // was @Transactional
    public AgentCheckpoint denyAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty())
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        AgentCheckpoint cp = cpOpt.get();
        if (cp.getBatchToolDecisions() == null) {
            log.warnf("No batch tool decisions found for session %s, cannot denyAll", sessionId);
            return null;
        }
        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        if (pendingTools != null) {
            for (String pending : pendingTools) {
                if (pending != null) {
                    agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, AgentSentinels.TOOL_DECISION_DENIED);
                    agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
                        sessionId, AgentSentinels.TOOL_DECISION_DENIED, pending, null, reason, Instant.now()));
                }
            }
        }
        log.infof("Denied ALL tools for session %s reason %s", sessionId, reason);
        return cp;
    }

    /**
     * Must be called OUTSIDE any active transaction, after approveTool/denyTool/
     * approveAll/denyAll has committed. This ensures the SQLite write lock is fully
     * released before resumeAfterBatchDecision attempts its own writes.
     */
    public void triggerResume(AgentCheckpoint cp) {
        if (cp == null) return;
        
        // findLatestAwaitingApproval may return empty if resumeAfterBatchDecision
        // already transitioned the status — fall back to the pre-transition cp safely.
        AgentCheckpoint fresh = agentStore.findLatestAwaitingApproval(cp.getSessionId())
            .orElse(cp);

        String userId = fresh.getChannelPlatformId() != null
                    ? fresh.getChannelPlatformId() : fresh.getSessionId();
        String channelId = fresh.getChannelPlatformId() != null
                        ? fresh.getChannelPlatformId() : fresh.getSessionId();
        String channelType = fresh.getChannelType() != null
                        ? fresh.getChannelType() : "websocket";

        log.debugf("resumeCheckpoint: session=%s channelType=%s channelId=%s",
                fresh.getSessionId(), channelType, channelId);

        resumeHandler.resumeAfterBatchDecision(
            fresh.getSessionId(), fresh.getAgentRole(), userId, channelId, channelType);

        agentSseService.publish(fresh.getSessionId(), new AgentSseService.HitlEvent(
            fresh.getSessionId(), "RESUMED", null, null, null, Instant.now()
        ));
    }
}
