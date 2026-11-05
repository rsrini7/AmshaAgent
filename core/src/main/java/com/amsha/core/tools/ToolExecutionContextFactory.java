package com.amsha.core.tools;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;

import io.opentelemetry.api.trace.Span;

/** Builds execution context from the current workspace scope at the tool boundary. */
final class ToolExecutionContextFactory {
    private ToolExecutionContextFactory() {}

    static ToolExecutionContext create(SkillRegistry skillRegistry, String operationId) {
        return new ToolExecutionContext(
                WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : "system",
                WorkspaceContext.getUserIdOrUnknown(),
                WorkspaceContext.CHAT_ID.isBound() ? WorkspaceContext.CHAT_ID.get() : "system",
                WorkspaceContext.AGENT_ROLE.isBound()
                        ? WorkspaceContext.AGENT_ROLE.get()
                        : AgentRoleEnum.COORDINATOR.id(),
                WorkspaceContext.AUTONOMY.isBound() ? WorkspaceContext.AUTONOMY.get() : AutonomyLevel.SUPERVISED,
                Span.current().getSpanContext().getTraceId(),
                WorkspaceContext.CHANNEL_TYPE.isBound() ? WorkspaceContext.CHANNEL_TYPE.get() : null,
                WorkspaceContext.CHANNEL_PLATFORM_ID.isBound() ? WorkspaceContext.CHANNEL_PLATFORM_ID.get() : null,
                WorkspaceContext.RAW_USER_GOAL.isBound() ? WorkspaceContext.RAW_USER_GOAL.get() : null,
                WorkspaceContext.SUGGESTED_CRON.isBound() ? WorkspaceContext.SUGGESTED_CRON.get() : null,
                WorkspaceContext.SUGGESTED_NEXT_FIRE_TIME.isBound()
                        ? WorkspaceContext.SUGGESTED_NEXT_FIRE_TIME.get()
                        : null,
                WorkspaceContext.SUGGESTED_CRON_SOURCE.isBound() ? WorkspaceContext.SUGGESTED_CRON_SOURCE.get() : null,
                resolveSkillHome(skillRegistry),
                resolveBrowserPreview(skillRegistry),
                operationId,
                WorkspaceContext.activeWorkspaceId().orElse(null),
                null,
                0,
                0);
    }

    private static String resolveSkillHome(SkillRegistry skillRegistry) {
        String agentRole = WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null;
        if (agentRole == null || !AgentRoleEnum.isSkillRole(agentRole)) {
            return null;
        }
        String skillName = AgentRoleEnum.skillName(agentRole);
        return skillRegistry
                .findByName(skillName)
                .map(entry -> entry.definition().skillHome())
                .orElse(null);
    }

    private static boolean resolveBrowserPreview(SkillRegistry skillRegistry) {
        String agentRole = WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : null;
        if (agentRole == null || !AgentRoleEnum.isSkillRole(agentRole)) {
            return false;
        }
        String skillName = AgentRoleEnum.skillName(agentRole);
        return skillRegistry
                .findByName(skillName)
                .map(entry -> entry.definition().browserPreview())
                .orElse(false);
    }
}
