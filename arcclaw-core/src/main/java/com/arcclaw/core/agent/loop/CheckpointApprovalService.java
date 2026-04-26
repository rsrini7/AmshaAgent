package com.arcclaw.core.agent.loop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.agent.sse.HitlEventType;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
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

    // ✅ ArchitectureBoundaryTest: No @Transactional in .agent.loop package
    // doUpdateBatchToolDecision is @Transactional(REQUIRES_NEW) and manages
    // its own connection. An outer REQUIRED tx on Connection-1 holds a reserved
    // SQLite write lock (XA BEGIN IMMEDIATE) that blocks the inner COMMIT.
    // Reads (findLatestAwaitingApproval, isAllBatchToolsDecided) work fine
    // without an outer tx — SQLite auto-commits SELECT statements.
    public AgentCheckpoint approveTool(String sessionId, String toolName, String reason) {
        AgentCheckpoint cp = resolveAwaitingCheckpoint(sessionId);
        String actualSid = cp.getSessionId();
        
        agentStore.updateBatchToolDecision(actualSid, cp.getAgentRole(), toolName, ToolDecision.APPROVED.name());
        log.infof("Approved tool %s for session %s reason %s", toolName, actualSid, reason);
        agentSseService.publish(actualSid,
            new AgentSseService.HitlEvent(actualSid, HitlEventType.APPROVED.wireValue(), toolName, null, reason, Instant.now()));
        
        boolean allDecided = agentStore.isAllBatchToolsDecided(actualSid, cp.getAgentRole());
        return allDecided ? cp : null;
    }

    public AgentCheckpoint denyTool(String sessionId, String toolName, String reason) {
        AgentCheckpoint cp = resolveAwaitingCheckpoint(sessionId);
        String actualSid = cp.getSessionId();

        agentStore.updateBatchToolDecision(actualSid, cp.getAgentRole(), toolName, ToolDecision.DENIED.name());
        log.infof("Denied tool %s for session %s reason %s", toolName, actualSid, reason);
        agentSseService.publish(actualSid,
            new AgentSseService.HitlEvent(actualSid, HitlEventType.DENIED.wireValue(), toolName, null, reason, Instant.now()));
        
        boolean allDecided = agentStore.isAllBatchToolsDecided(actualSid, cp.getAgentRole());
        return allDecided ? cp : null;
    }

    public AgentCheckpoint approveAll(String sessionId, String reason) {
        AgentCheckpoint cp = resolveAwaitingCheckpoint(sessionId);
        String actualSid = cp.getSessionId();

        if (cp.getBatchToolDecisions() == null) {
            log.warnf("No batch tool decisions found for session %s, cannot approveAll", actualSid);
            return null;
        }
        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        if (pendingTools != null) {
            for (String pending : pendingTools) {
                if (pending != null) {
                    agentStore.updateBatchToolDecision(actualSid, cp.getAgentRole(), pending, ToolDecision.APPROVED.name()); 
                    agentSseService.publish(actualSid, new AgentSseService.HitlEvent(
                        actualSid, HitlEventType.APPROVED.wireValue(), pending, null, reason, Instant.now()));
                }
            }
        }
        log.infof("Approved ALL tools for session %s reason %s", actualSid, reason);
        return cp;
    }

    public AgentCheckpoint denyAll(String sessionId, String reason) {
        AgentCheckpoint cp = resolveAwaitingCheckpoint(sessionId);
        String actualSid = cp.getSessionId();

        if (cp.getBatchToolDecisions() == null) {
            log.warnf("No batch tool decisions found for session %s, cannot denyAll", actualSid);
            return null;
        }
        List<String> pendingTools = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
        if (pendingTools != null) {
            for (String pending : pendingTools) {
                if (pending != null) {
                    agentStore.updateBatchToolDecision(actualSid, cp.getAgentRole(), pending, ToolDecision.DENIED.name());
                    agentSseService.publish(actualSid, new AgentSseService.HitlEvent(
                        actualSid, HitlEventType.DENIED.wireValue(), pending, null, reason, Instant.now()));
                }
            }
        }
        log.infof("Denied ALL tools for session %s reason %s", actualSid, reason);
        return cp;
    }

    /**
     * Resolves the correct AWAITING_APPROVAL checkpoint. 
     * Handles both direct sessionId and segments (e.g. cronId).
     */
    private AgentCheckpoint resolveAwaitingCheckpoint(String idOrSid) {
        // 1. Try direct sessionId lookup
        Optional<AgentCheckpoint> cpOpt = agentStore.findLatestAwaitingApproval(idOrSid);
        if (cpOpt.isPresent()) return cpOpt.get();

        // 2. Try segment lookup (for cron-sessions which may have extended IDs containing the UUID)
        cpOpt = agentStore.findLatestAwaitingApprovalBySessionContaining(idOrSid);
        if (cpOpt.isPresent()) return cpOpt.get();

        throw new CheckpointResolutionException(
            "No pending checkpoint found for session or task " + idOrSid, 404);
    }

    /**
     * Must be called OUTSIDE any active transaction, after approveTool/denyTool/
     * approveAll/denyAll has committed. This ensures the SQLite write lock is fully
     * released before resumeAfterBatchDecision attempts its own writes.
     */
    public void triggerResume(AgentCheckpoint cp) {
        if (cp == null) return;

        String sessionId = cp.getSessionId();
        String agentRole = cp.getAgentRole();

        // §7.29 / §T5-4: Synchronously transition AWAITING_APPROVAL -> RESUMING in the DB.
        // This prevents CheckpointResumeCoordinator from archiving the checkpoint as "stale"
        // if a new user message arrives in the millisecond gap before the executor
        // thread picks up the task.
        boolean claimed = agentStore.claimForResume(sessionId, agentRole);
        if (!claimed) {
             log.infof("[RESUME-DB-CLAIM] session=%s already claimed — skipping trigger.", sessionId);
             return;
        }

        try {
            // findLatestNonArchivedCheckpoint instead of findLatestAwaitingApproval 
            // since we just moved it to RESUMING.
            AgentCheckpoint fresh = agentStore.findLatestNonArchivedCheckpoint(sessionId)
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
                fresh.getSessionId(), HitlEventType.RESUMED.wireValue(), null, null, null, Instant.now()
            ));
        } catch (Exception e) {
            log.errorf("Failed to trigger resume for session %s: %s", sessionId, e.getMessage());
            agentStore.clearBatchResuming(sessionId);
            throw e;
        }
    }
}
