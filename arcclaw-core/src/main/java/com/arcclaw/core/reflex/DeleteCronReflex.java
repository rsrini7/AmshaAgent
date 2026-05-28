package com.arcclaw.core.reflex;

import static com.arcclaw.core.message.UserMessages.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.persistence.api.CronStorePort;

/**
 * Fast-path reflex for deleting a specific cron job by ID.
 * Handles three input forms:
 *   - "deletecron <id>"       - REST reflex / direct
 *   - "/deletecron <id>"      - slash form
 *   - "/cron delete <id>"     - Telegram reflex dispatch form
 */
@ApplicationScoped
@ReflexPriority(500)
public class DeleteCronReflex implements AgentReflex {

    @Inject
    CronStorePort cronStore;

    @Inject
    AuditService auditService;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String t = input.trim().toLowerCase();
        return t.startsWith("deletecron") || t.startsWith("/deletecron") || t.startsWith("/cron delete");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {

        if (!WorkspaceScopeHolder.USER_ID.isBound()) {
            return ReflexResult.respond(DELETE_CONTEXT_NOT_AVAILABLE, context);
        }

        String uid = WorkspaceScopeHolder.USER_ID.get();

        // Normalize prefix - extract cronId regardless of which reflex form was used
        String normalized = input.trim();
        String lower = normalized.toLowerCase();
        int prefixLen;
        if (lower.startsWith("/cron delete")) prefixLen = "/cron delete".length();
        else if (lower.startsWith("/deletecron")) prefixLen = "/deletecron".length();
        else prefixLen = "deletecron".length();

        String cronId = normalized.substring(prefixLen).trim();

        if (cronId.isBlank()) {
            return ReflexResult.respond(USAGE_DELETE_CRON, context);
        }

        // UUID format guard - prevents DB roundtrip for garbage input
        if (!cronId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return ReflexResult.respond(UserMessages.cronInvalid(cronId), context);
        }

        // Tenant-isolated delete - user B cannot delete user A's job
        int deleted = cronStore.deleteByIdAndUserId(cronId, uid);
        if (deleted == 0) {
            return ReflexResult.respond(UserMessages.cronNotFound(cronId), context);
        }

        auditService.record(
                sessionId,
                uid,
                AgentRoleEnum.COORDINATOR.toScopedName(),
                SecurityEventType.CRONDELETED,
                "cronId=" + cronId + " via=reflex");

        return ReflexResult.respond(UserMessages.cronDeleted(cronId), context);
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        return input;
    }
}
