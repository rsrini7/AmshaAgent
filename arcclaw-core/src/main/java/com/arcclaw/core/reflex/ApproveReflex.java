package com.arcclaw.core.reflex;

import java.util.Optional;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.DevSecretSeeder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class ApproveReflex implements AgentReflex {

    @Inject
    Logger log;

    @Inject
    StatefulAgentStore statefulAgentStore;


    @Inject
    Instance<DevSecretSeeder> devSeeder;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Override
    public boolean matches(String input) {
        return input != null && (input.trim().toLowerCase().equalsIgnoreCase("/approve") || input.trim().equalsIgnoreCase("approve"));
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
        try {
            return WorkspaceScopeHolder.call(sessionId, () -> {
                String activeRole = null;
                for (AgentRoleEnum role : AgentRoleEnum.values()) {
                    Optional<AgentCheckpoint> cpOpt = statefulAgentStore.load(sessionId, role.toScopedName());
                    if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                        activeRole = role.toScopedName();
                        break;
                    }
                }

                if (activeRole == null) {
                    return "No pending tool executions require approval for this session.";
                }

                log.infof("[ApproveReflex] User approved pending tool execution for session: %s", sessionId);
                if (!devSeeder.isUnsatisfied()) {
                    devSeeder.get().seedDevSecrets();
                }

                final String finalRole = activeRole;
                AgentResult result = WorkspaceContext.runWithFullAutonomy(
                        sessionId,
                        WorkspaceContext.userId(),
                        WorkspaceContext.chatId(),
                        finalRole,
                        () -> agentLoop.run(sessionId, finalRole, "Resuming task after approval", reasoningEngine, 5)
                );

                if (result instanceof AgentResult.Finished f) {
                    return f.answer();
                } else {
                    String failReason = switch (result) {
                        case AgentResult.Failed fail -> fail.reason();
                        case AgentResult.LoopExceeded loop -> "Loop exceeded " + loop.maxIterations() + " iterations";
                        case AgentResult.AwaitingApproval a -> "AWAITING_APPROVAL (Unexpected in resume)";
                        default -> "Unknown error";
                    };
                    String sanitizedReason = AgentErrorMessageUtil.sanitizeErrorMessage(failReason);
                    return "❌ **Task Failed after approval**\n\nReason: " + sanitizedReason;
                }
            });
        } catch (Exception e) {
            log.errorf("Failed to process approval for session %s: %s", sessionId, e.getMessage(), e);
            return "❌ **Approval Error**: An internal fault occurred while resuming the task.";
        }
    }
}
