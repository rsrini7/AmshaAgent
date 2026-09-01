package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.loop.GroupAnalyticsService;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.role.AgentGroup;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupConsultationDelegatorTest {
    private static final String SESSION_ID = "session-1";
    private static final String GOAL = "Review the security implications of this change";
    private static final String LEAD_ROLE_ID = AgentRoleEnum.DEBUGGER_ID;

    private final GroupConsultationEngine groupConsultationEngine = mock(GroupConsultationEngine.class);
    private final GroupConsultationService groupConsultationService = mock(GroupConsultationService.class);
    private final GroupAnalyticsService groupAnalytics = mock(GroupAnalyticsService.class);
    private final TaskClassifierService taskClassifier = mock(TaskClassifierService.class);
    private final GroupConsultationDelegator delegator = new GroupConsultationDelegator();

    @BeforeEach
    void setUp() {
        delegator.groupConsultationService = groupConsultationService;
        delegator.groupConsultationEngine = groupConsultationEngine;
        delegator.groupAnalytics = groupAnalytics;
        delegator.taskClassifier = taskClassifier;
        delegator.delegationProcessor = null;
    }

    @Test
    void prepareGoal_missingRegistry_returnsOriginalGoal() {
        when(groupConsultationEngine.hasGroupRegistry()).thenReturn(false);

        assertEquals(GOAL, delegator.prepareGoal(context(), SESSION_ID, LEAD_ROLE_ID, GOAL));
    }

    @Test
    void prepareGoal_usefulResults_addsSynthesisPrompt() {
        AgentGroup group = availableGroup();
        Map<String, String> subTasks = Map.of("sentinel", "Review security risks");
        var consultation = new GroupConsultationEngine.GroupConsultationResult(
                group.groupId(), Map.of("sentinel", "No critical risk found"), null, 1, 0);
        stubConsultation(group, subTasks, consultation);

        String prepared = delegator.prepareGoal(context(), SESSION_ID, LEAD_ROLE_ID, GOAL);

        assertTrue(prepared.startsWith(GOAL + "\n\n"));
        assertTrue(prepared.contains("GROUP CONSULTATION RESULTS"));
        assertTrue(prepared.contains("No critical risk found"));
        verify(groupAnalytics).recordOutcome(eq(group.groupId()), eq(List.of("security")), eq(1), eq(0), eq(true));
    }

    @Test
    void prepareGoal_noUsefulResults_returnsOriginalGoal() {
        AgentGroup group = availableGroup();
        Map<String, String> subTasks = Map.of("sentinel", "Review security risks");
        var consultation = new GroupConsultationEngine.GroupConsultationResult(
                group.groupId(), Map.of("sentinel", "[Failed: timeout]"), null, 0, 1);
        stubConsultation(group, subTasks, consultation);

        assertEquals(GOAL, delegator.prepareGoal(context(), SESSION_ID, LEAD_ROLE_ID, GOAL));

        verify(groupAnalytics).recordOutcome(eq(group.groupId()), eq(List.of("security")), eq(0), eq(1), eq(false));
    }

    private AgentGroup availableGroup() {
        return AgentGroupRegistry.KAILASA;
    }

    private void stubConsultation(
            AgentGroup group,
            Map<String, String> subTasks,
            GroupConsultationEngine.GroupConsultationResult consultation) {
        when(groupConsultationEngine.hasGroupRegistry()).thenReturn(true);
        when(groupConsultationService.getGroup(LEAD_ROLE_ID)).thenReturn(Optional.of(group));
        when(groupConsultationEngine.findGroupById(group.groupId())).thenReturn(Optional.of(group));
        when(groupConsultationEngine.buildSubTasks(group, GOAL)).thenReturn(subTasks);
        when(groupConsultationEngine.consultGroup(eq(group.groupId()), eq(GOAL), eq(subTasks), any()))
                .thenReturn(consultation);
        when(taskClassifier.classify(GOAL)).thenReturn(List.of(TaskClassifierService.TaskDomain.SECURITY));
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(
                SESSION_ID,
                "user-1",
                "chat-1",
                LEAD_ROLE_ID,
                AutonomyLevel.FULL,
                "trace-1",
                "test",
                "platform",
                GOAL,
                null,
                null,
                null);
    }
}
