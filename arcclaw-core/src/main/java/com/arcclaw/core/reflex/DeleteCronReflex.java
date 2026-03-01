package com.arcclaw.core.reflex;

import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCronRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fast-path reflex for deleting a specific cron job by ID.
 * Usage: {@code deletecron <id>}
 */
@ApplicationScoped
public class DeleteCronReflex implements AgentReflex {

    @Inject
    AgentCronRepository cronRepository;

    @Inject
    AuditService auditService;

    @Override
    public boolean matches(String input) {
        return input != null
                && input.trim().toLowerCase().startsWith("deletecron");
    }

    @Override
    public String execute(String sessionId, String userId, String input) {
        if (!WorkspaceScopeHolder.USER_ID.isBound()) {
            return "Cannot delete schedule — user context not available.";
        }
        String uid = WorkspaceScopeHolder.USER_ID.get();
        String cronId = input.trim().substring("deletecron".length()).trim();
        if (cronId.isBlank()) {
            return "Usage: deletecron <id>";
        }

        cronRepository.deleteByIdAndUserId(cronId, uid);

        auditService.record(sessionId, uid,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONDELETED,
                "cronId=" + cronId + " via=reflex");

        return "Cron job " + cronId + " deleted.";
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}
