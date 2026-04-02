package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.core.persistence.AsyncPersistenceEngine;
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
    @Inject AsyncPersistenceEngine asyncEngine;

    @Transactional
    public void approveTool(String sessionId, String toolName, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
        }
        AgentCheckpoint cp = cpOpt.get();
        agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), toolName, "APPROVED");
        log.infof("Approved tool %s for session %s reason: %s", toolName, sessionId, reason);
        asyncEngine.persistAndWait(cp).join();
        if (agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole())) {
            resumeCheckpoint(cp);
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
        asyncEngine.persistAndWait(cp).join();
        if (agentStore.isAllBatchToolsDecided(sessionId, cp.getAgentRole())) {
            resumeCheckpoint(cp);
        }
    }

    @Transactional
    public void approveAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
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
                    agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, "APPROVED");
                }
            }
        }
        asyncEngine.persistAndWait(cp).join();
        log.infof("Approved ALL tools for session %s reason: %s", sessionId, reason);
        resumeCheckpoint(cp);
    }

    @Transactional
    public void denyAll(String sessionId, String reason) {
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(sessionId);
        if (cpOpt.isEmpty()) {
            throw new CheckpointResolutionException("No pending checkpoint found for session " + sessionId, 404);
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
                    agentStore.updateBatchToolDecision(sessionId, cp.getAgentRole(), pending, "DENIED");
                }
            }
        }
        asyncEngine.persistAndWait(cp).join();
        log.infof("Denied ALL tools for session %s reason: %s", sessionId, reason);
        resumeCheckpoint(cp);
    }
    
    private void resumeCheckpoint(AgentCheckpoint cp) {
        String userId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : cp.getSessionId();
        String channelId = cp.getChannelPlatformId() != null ? cp.getChannelPlatformId() : cp.getSessionId();
        String channelType = cp.getChannelType() != null ? cp.getChannelType() : "websocket";
        
        resumeHandler.resumeAfterBatchDecision(
            cp.getSessionId(), cp.getAgentRole(), userId, channelId, channelType);
    }
}
