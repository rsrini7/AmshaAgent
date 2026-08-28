package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Locale;

import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.GroupAnalyticsService;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolExecutionContext;

import org.jboss.logging.Logger;

/**
 * Owns the family-huddle part of delegation and returns the goal that the
 * normal delegation path should execute. Keeping this orchestration behind a
 * small seam keeps {@link DelegateToAgentTool} focused on tool admission and
 * dispatch while preserving one execution path for the lead agent.
 */
@ApplicationScoped
public class GroupConsultationDelegator {
    private static final Logger log = Logger.getLogger(GroupConsultationDelegator.class);

    @Inject
    Instance<AgentLoop> agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    GroupConsultationService groupConsultationService;

    @Inject
    GroupConsultationEngine groupConsultationEngine;

    @Inject
    GroupAnalyticsService groupAnalytics;

    @Inject
    TaskClassifierService taskClassifier;

    @Inject
    DelegationProcessor delegationProcessor;

    public boolean isGroupLead(String leadRoleId) {
        return groupConsultationService != null && groupConsultationService.isGroupLead(leadRoleId);
    }

    /**
     * Runs the consultation when the group infrastructure is available. A
     * missing group or empty member set is a normal degradation to the lead;
     * the returned goal is still safe to pass through normal delegation.
     */
    public String prepareGoal(ToolExecutionContext ctx, String outerSessionId, String leadRoleId, String goal) {
        if (groupConsultationEngine == null || !groupConsultationEngine.hasGroupRegistry()) {
            log.warnf(
                    "[GROUP_CONSULT] Group registry unavailable; continuing without consultation for lead=%s",
                    leadRoleId);
            return goal;
        }

        String groupId = groupConsultationService
                .getGroup(leadRoleId)
                .map(group -> group.groupId())
                .orElse("");
        var groupOpt = groupConsultationEngine.findGroupById(groupId);
        if (groupOpt.isEmpty()) {
            log.warnf(
                    "[GROUP_CONSULT] No group found for lead=%s group=%s; using lead-only delegation",
                    leadRoleId, groupId.isBlank() ? "<none>" : groupId);
            return delegationProcessor != null
                    ? delegationProcessor.enhanceGoalWithGroupConsultation(leadRoleId, goal)
                    : goal;
        }

        var group = groupOpt.get();
        var subTasks = groupConsultationEngine.buildSubTasks(group, goal);
        if (subTasks.isEmpty()) {
            log.warnf("[GROUP_CONSULT] No consultation subtasks for group=%s; using original goal", group.groupId());
            return goal;
        }

        GroupConsultationEngine.SubAgentRunner runner =
                (memberId, memberGoal) -> runMember(ctx, outerSessionId, memberId, memberGoal);
        var consultResult = groupConsultationEngine.consultGroup(group.groupId(), goal, subTasks, runner);

        var domains = taskClassifier.classify(goal);
        groupAnalytics.recordOutcome(
                consultResult.groupId(),
                domains.stream()
                        .map(domain -> domain.name().toLowerCase(Locale.ROOT))
                        .toList(),
                consultResult.membersConsulted(),
                consultResult.membersFailed(),
                consultResult.hasUsefulResults());
        if (!consultResult.hasUsefulResults()) {
            log.warnf(
                    "[GROUP_CONSULT] No useful consultation results for group=%s; using original goal",
                    consultResult.groupId());
            return goal;
        }
        return goal + "\n\n" + consultResult.toSynthesisPrompt();
    }

    private String runMember(ToolExecutionContext ctx, String outerSessionId, String memberId, String memberGoal)
            throws Exception {
        AgentRoleEnum memberRole;
        try {
            memberRole = AgentRoleEnum.fromString(memberId);
        } catch (IllegalArgumentException e) {
            return "[Skipped: unknown role '" + memberId + "']";
        }

        int maxIterations = Math.max(1, memberRole.definition().maxIterations());
        String memberSession = outerSessionId + AgentSentinels.DELEGATE_SESSION_SENTINEL + memberId + ":consult:"
                + System.currentTimeMillis();
        AgentResult result = WorkspaceContext.run(
                memberSession,
                ctx.userId(),
                ctx.chatId(),
                ctx.autonomyLevel(),
                memberRole.toScopedName(),
                ctx.channelType(),
                ctx.channelPlatformId(),
                () -> agentLoop
                        .get()
                        .run(
                                memberSession,
                                memberRole.toScopedName(),
                                memberGoal,
                                reasoningEngine,
                                maxIterations,
                                DelegatedMessageContextFactory.create(
                                        memberSession,
                                        ctx.userId(),
                                        ctx.chatId(),
                                        ctx.channelType(),
                                        memberGoal,
                                        memberRole.toScopedName(),
                                        ctx.autonomyLevel())));

        return switch (result) {
            case AgentResult.Finished finished -> finished.answer();
            case AgentResult.Failed failed -> "[Failed: " + failed.reason() + "]";
            default -> "[No output]";
        };
    }
}
