package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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
    @Inject CheckpointApprovalService self;  // ← self-injection for REQUIRES_NEW boundary

    @Transactional
    public void approveTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, "APPROVED");
        log.infof("Approved tool %s for session %s reason: %s", toolName, sessionId, reason);
        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, "APPROVED", toolName, null, reason, Instant.now()
        ));
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        log.debugf("isAllBatchToolsDecided for session %s = %b", sessionId, allDecided);
        if (allDecided) {
            self.resumeCheckpointAfterCommit(cp);
        }
    }

    @Transactional
    public void denyTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, "DENIED");
        log.infof("Denied tool %s for session %s reason: %s", toolName, sessionId, reason);
        agentSseService.publish(sessionId, new AgentSseService.HitlEvent(
            sessionId, "DENIED", toolName, null, reason, Instant.now()
        ));
        boolean allDecided = agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole());
        log.debugf("isAllBatchToolsDecided for session %s = %b", sessionId, allDecided);
        if (allDecided) {
            self.resumeCheckpointAfterCommit(cp);
        }
    }

    @Transactional
    public void approveAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        if (cp.getBatchToolDecisions() == null) {
            log.warnf("No batch tool decisions found for session %s, cannot approveAll", sessionId);
            return;
        }

        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        if (pendingTools != null) {
            for (String pending : pendingTools) {
                if (pending != null) {
                    agentStore.updateBatchToolDecision(
                        sessionId, cp.getAgentRole(), pending, "APPROVED");
                }
            }
        }

        log.infof("Approved ALL tools for session %s reason: %s", sessionId, reason);
        self.resumeCheckpointAfterCommit(cp);
    }

    @Transactional
    public void denyAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException(
                "No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        if (cp.getBatchToolDecisions() == null) {
            log.warnf("No batch tool decisions found for session %s, cannot denyAll", sessionId);
            return;
        }

        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        if (pendingTools != null) {
            for (String pending : pendingTools) {
                if (pending != null) {
                    agentStore.updateBatchToolDecision(
                        sessionId, cp.getAgentRole(), pending, "DENIED");
                }
            }
        }

        log.infof("Denied ALL tools for session %s reason: %s", sessionId, reason);
        self.resumeCheckpointAfterCommit(cp);
    }

    /**
     * Runs resumeCheckpoint in a REQUIRES_NEW transaction so the outer
     * approveTool/denyTool/approveAll/denyAll transaction suspends before
     * resumeAfterBatchDecision starts writing. This prevents SQLITE_BUSY_SNAPSHOT
     * caused by the outer transaction holding a write lock on agent_checkpoint
     * while the resume tries to write the same table.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void resumeCheckpointAfterCommit(AgentCheckpoint cp) {
        resumeCheckpoint(cp);
    }

    private void resumeCheckpoint(AgentCheckpoint cp) {
        // Reload fresh — cp may be stale from before updateBatchToolDecision committed
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
