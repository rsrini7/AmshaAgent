package com.arcclaw.core.agent.loop;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import org.jboss.logging.Logger;

/**
 * Persists AWAITING_APPROVAL checkpoints and produces the corresponding AgentResult.
 */
@ApplicationScoped
public class ApprovalCheckpointManager {

    private static final Logger log = Logger.getLogger(ApprovalCheckpointManager.class);

    @Inject StatefulAgentStore agentStore;

    /**
     * Persists an AWAITING_APPROVAL checkpoint and returns the approval-pending AgentResult.
     * Captures the original user goal so RAG context enrichment on resume uses the
     * correct embedding query instead of the synthetic resume sentinel string.
     */
    AgentResult persist(String sessionId, String agentRole,
            String toolName, String paramsJson,
            List<ReasoningStep> history, String goal) {
        AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
                .orElseGet(() -> {
                    AgentCheckpoint c = new AgentCheckpoint();
                    c.setSessionId(sessionId);
                    c.setAgentRole(agentRole);
                    return c;
                });
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName(toolName);
        cp.setPendingToolParametersJson(paramsJson);
        cp.setOriginalGoal(goal);
        cp.setUpdatedAt(LocalDateTime.now());
        agentStore.save(cp);
        log.infof("STATE-MACHINE Pausing session %s for human approval of tool %s", sessionId, toolName);
        return AgentResult.awaitingApproval(toolName, paramsJson, sessionId, List.copyOf(history));
    }
}
