package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.Callable;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.state.AgentCronEntity;
import com.arcclaw.core.state.AgentCronRepository;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScheduleTaskTool}.
 * Uses WorkspaceScopeHolder.run() (not call()) because call() does not bind CHAT_ID.
 */
class ScheduleTaskToolTest {

    private final AgentCronRepository cronRepository = mock(AgentCronRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private ScheduleTaskTool createTool() {
        ScheduleTaskTool tool = new ScheduleTaskTool();
        try {
            var repoField = ScheduleTaskTool.class.getDeclaredField("cronRepository");
            repoField.setAccessible(true);
            repoField.set(tool, cronRepository);

            var auditField = ScheduleTaskTool.class.getDeclaredField("auditService");
            auditField.setAccessible(true);
            auditField.set(tool, auditService);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks", e);
        }
        return tool;
    }

    // Add a helper to your test class
    private <T> T executeInScope(String suggestedCron, Callable<T> task) throws Exception {
        return WorkspaceContext.run(
                "test-session-id",
                "test-user",
                "test-chat",
                AutonomyLevel.SUPERVISED,
                "coordinator",
                ChannelType.TELEGRAM,
                () -> ScopedValue.where(WorkspaceContext.SUGGESTED_CRON, suggestedCron).call(task::call));
    }

    @Test
    void testValidCronSchedulesSuccessfully() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolResponse result = executeInScope("0 9 * * 1", () -> tool.execute(Map.of(
                "goal", "Send weekly status report to the team via Telegram"
        )));

        assertTrue(result.success(), "Valid cron should schedule successfully: " + result.output());
        assertTrue(result.output().contains("Task scheduled successfully"));
        assertTrue(result.output().contains("ID:"));
        assertTrue(result.output().contains("Next fire:"));

        verify(cronRepository).persist(any(AgentCronEntity.class));
        verify(auditService).record(
                eq("test-session-id"), eq("test-user"), eq("coordinator"),
                eq(SecurityEventType.CRONCREATED), anyString()
        );
    }

    @Test
    void testInvalidCronExpressionReturnsFailure() throws Exception {
        ScheduleTaskTool tool = createTool();

        // Note: With the new architecture, the tool doesn't see 'invalid-cron' 
        // from the params. It uses whatever is in suggestedCron.
        ToolResponse result = executeInScope(null, () -> tool.execute(Map.of(
                "goal", "Send status report"
        )));

        assertFalse(result.success());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Schedule context missing"));

        verify(cronRepository, never()).persist(any());
    }

    @Test
    void testNakedPronounGoalRejected() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolResponse result = executeInScope("0 9 * * 1", () -> tool.execute(Map.of(
                "goal", "Deploy it to production"
        )));

        assertFalse(result.success(), "Naked pronoun 'it' should be rejected");
        assertEquals(422, result.exitCode());
        assertTrue(result.output().contains("unresolved references"));

        verify(cronRepository, never()).persist(any());
    }

    @Test
    void testMissingUserIdReturnsFailure403() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolResponse result = WorkspaceContext.run(
                "test-session-id",
                "",          // ← blank userId triggers the 403
                "test-chat",
                AutonomyLevel.SUPERVISED,
                "coordinator",
                ChannelType.TELEGRAM,
                () -> ScopedValue.where(WorkspaceContext.SUGGESTED_CRON, "0 9 * * 1").call(() -> tool.execute(Map.of(
                        "goal", "Send report"
                ))));

        assertFalse(result.success());
        assertEquals(403, result.exitCode());
        assertTrue(result.output().contains("tenant context not bound"));

        verify(cronRepository, never()).persist(any());
    }

    @Test
    void testScheduleAutonomousOperationDeclared() {
        ScheduleTaskTool tool = createTool();
        assertEquals(ToolOperation.SCHEDULE_AUTONOMOUS, tool.requiredOperation());
    }

    @Test
    void testRunOnceTruePassedThrough() throws Exception {
        ScheduleTaskTool tool = createTool();

        executeInScope("30 14 15 3 *", () -> tool.execute(Map.of(
                "goal", "Send one-time reminder to check deployment status",
                "runOnce", true
        )));

        verify(cronRepository).persist(argThat(entity -> {
            AgentCronEntity e = (AgentCronEntity) entity;
            return e.runOnce;
        }));
    }
}
