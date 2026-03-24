package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
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

    /**
     * Helper: runs tool.execute() inside WorkspaceScopeHolder.run() with proper
     * USER_ID + CHAT_ID binding and extracts the result via AtomicReference.
     */
    private ToolResponse executeInScope(ScheduleTaskTool tool, Map<String, Object> params) {
        AtomicReference<ToolResponse> ref = new AtomicReference<>();
        WorkspaceScopeHolder.run("test-session", "user-1", "chat-1", () ->
                ref.set(tool.execute(params))
        );
        return ref.get();
    }

    @Test
    void testValidCronSchedulesSuccessfully() {
        ScheduleTaskTool tool = createTool();

        ToolResponse result = executeInScope(tool, Map.of(
                "cronExpression", "0 9 * * 1",
                "goal", "Send weekly status report to the team via Telegram"
        ));

        assertTrue(result.success(), "Valid cron should schedule successfully: " + result.output());
        assertTrue(result.output().contains("Task scheduled successfully"));
        assertTrue(result.output().contains("ID:"));
        assertTrue(result.output().contains("Next fire:"));

        verify(cronRepository).persist(any(AgentCronEntity.class));
        verify(auditService).record(
                eq("test-session"), eq("user-1"), eq("coordinator"),
                eq(SecurityEventType.CRONCREATED), anyString()
        );
    }

    @Test
    void testInvalidCronExpressionReturnsFailure() {
        ScheduleTaskTool tool = createTool();

        ToolResponse result = executeInScope(tool, Map.of(
                "cronExpression", "invalid-cron",
                "goal", "Send status report"
        ));

        assertFalse(result.success());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Invalid cron expression"));

        verify(cronRepository, never()).persist(any());
    }

    @Test
    void testNakedPronounGoalRejected() {
        ScheduleTaskTool tool = createTool();

        ToolResponse result = executeInScope(tool, Map.of(
                "cronExpression", "0 9 * * 1",
                "goal", "Deploy it to production"
        ));

        assertFalse(result.success(), "Naked pronoun 'it' should be rejected");
        assertEquals(422, result.exitCode());
        assertTrue(result.output().contains("unresolved references"));

        verify(cronRepository, never()).persist(any());
    }

    @Test
    void testMissingUserIdReturnsFailure403() {
        ScheduleTaskTool tool = createTool();

        // Execute WITHOUT WorkspaceScopeHolder binding — no tenant context
        ToolResponse result = tool.execute(Map.of(
                "cronExpression", "0 9 * * 1",
                "goal", "Send report"
        ));

        assertFalse(result.success());
        assertEquals(403, result.exitCode());
        assertTrue(result.output().contains("Security violation"));
        assertTrue(result.output().contains("tenant context not bound"));

        verify(cronRepository, never()).persist(any());
    }

    @Test
    void testScheduleAutonomousOperationDeclared() {
        ScheduleTaskTool tool = createTool();
        assertEquals(ToolOperation.SCHEDULE_AUTONOMOUS, tool.requiredOperation());
    }

    @Test
    void testRunOnceTruePassedThrough() {
        ScheduleTaskTool tool = createTool();

        WorkspaceScopeHolder.run("test-session", "user-1", "chat-1", () -> {
            tool.execute(Map.of(
                    "cronExpression", "30 14 15 3 *",
                    "goal", "Send one-time reminder to check deployment status",
                    "runOnce", true
            ));
        });

        verify(cronRepository).persist(argThat(entity -> {
            AgentCronEntity e = (AgentCronEntity) entity;
            return e.runOnce;
        }));
    }
}
