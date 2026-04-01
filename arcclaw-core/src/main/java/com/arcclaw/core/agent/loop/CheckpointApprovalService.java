package com.arcclaw.core.agent.loop;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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

    @Transactional
    public void approveTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, "APPROVED");
        log.infof("Approved tool %s for session %s reason: %s", toolName, sessionId, reason);
        if (agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole())) {
            String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
            String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
            resumeHandler.resumeAfterBatchDecision(
                sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
        }
    }

    @Transactional
    public void denyTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, "DENIED");
        log.infof("Denied tool %s for session %s reason: %s", toolName, sessionId, reason);
        if (agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole())) {
            String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
            String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
            resumeHandler.resumeAfterBatchDecision(
                sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
        }
    }

    @Transactional
    public void approveAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        for (String pending : BatchDecisionUtil.pendingToolNames(cp, objectMapper)) {
            agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, "APPROVED");
        }
        log.infof("Approved ALL tools for session %s reason: %s", sessionId, reason);
        String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        resumeHandler.resumeAfterBatchDecision(sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
    }

    @Transactional
    public void denyAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        for (String pending : BatchDecisionUtil.pendingToolNames(cp, objectMapper)) {
            agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, "DENIED");
        }
        log.infof("Denied ALL tools for session %s reason: %s", sessionId, reason);
        String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : sessionId;
        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        resumeHandler.resumeAfterBatchDecision(sessionId, cp.getAgentRole(), userId, cp.getChannelPlatformId(), channelType);
    }
}
