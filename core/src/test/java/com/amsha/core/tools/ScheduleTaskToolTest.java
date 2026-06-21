package com.amsha.core.tools;

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

import jakarta.enterprise.event.Event;

import java.util.Map;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.cron.CronTaskScheduled;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.ToolOperation;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S1192")
class ScheduleTaskToolTest {

    private final CronStorePort cronStore = mock(CronStorePort.class);
    private final AuditService auditService = mock(AuditService.class);

    @SuppressWarnings("unchecked")
    private final Event<CronTaskScheduled> cronScheduledEvent = mock(Event.class);

    private ScheduleTaskTool createTool() {
        ScheduleTaskTool tool = new ScheduleTaskTool();
        try {
            var repoField = ScheduleTaskTool.class.getDeclaredField("cronStore");
            repoField.setAccessible(true);
            repoField.set(tool, cronStore);

            var auditField = ScheduleTaskTool.class.getDeclaredField("auditService");
            auditField.setAccessible(true);
            auditField.set(tool, auditService);

            var eventField = ScheduleTaskTool.class.getDeclaredField("cronScheduledEvent");
            eventField.setAccessible(true);
            eventField.set(tool, cronScheduledEvent);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mocks", e);
        }
        return tool;
    }

    @Test
    void testValidCronSchedulesSuccessfully() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "0 9 * * 1",
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send weekly status report to the team via Telegram"));

        assertTrue(result.success(), "Valid cron should schedule successfully: " + result.output());
        assertTrue(result.output().contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE));
        assertTrue(result.output().contains("ID:"));
        assertTrue(result.output().contains("Next fire:"));

        verify(cronStore).save(any(AgentCronDto.class));
        verify(auditService)
                .record(
                        eq("test-session-id"),
                        eq("test-user"),
                        eq("coordinator"),
                        eq(SecurityEventType.CRONCREATED),
                        anyString());
    }

    @Test
    void testInvalidCronExpressionReturnsFailure() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                null,
                null,
                null);

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send status report"));

        assertFalse(result.success());
        assertEquals(400, result.exitCode());
        assertTrue(result.output().contains("Missing required parameter: cronExpression"));

        verify(cronStore, never()).save(any());
    }

    @Test
    void testNakedPronounGoalRejected() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "0 9 * * 1",
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Deploy this to production"));

        assertFalse(result.success(), "Naked pronoun 'this' should be rejected");
        assertEquals(422, result.exitCode());
        assertTrue(result.output().contains("unresolved references"));

        verify(cronStore, never()).save(any());
    }

    @Test
    void testMissingUserIdReturnsFailure403() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "0 9 * * 1",
                null,
                "UTC");

        ToolResponse result = tool.execute(ctx, Map.of("goal", "Send report"));

        assertFalse(result.success());
        assertEquals(403, result.exitCode());
        assertTrue(result.output().contains("tenant context not bound"));

        verify(cronStore, never()).save(any());
    }

    @Test
    void testScheduleAutonomousOperationDeclared() {
        ScheduleTaskTool tool = createTool();
        assertEquals(ToolOperation.SCHEDULE_AUTONOMOUS, tool.requiredOperation());
    }

    @Test
    void testRunOnceTruePassedThrough() throws Exception {
        ScheduleTaskTool tool = createTool();

        ToolExecutionContext ctx = new ToolExecutionContext(
                "test-session-id",
                "test-user",
                "test-chat",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "trace",
                "TELEGRAM",
                "platform1",
                "raw goal",
                "30 14 15 3 *",
                null,
                "UTC");

        tool.execute(ctx, Map.of("goal", "Send one-time reminder to check deployment status", "runOnce", true));

        verify(cronStore).save(argThat(dto -> {
            AgentCronDto e = (AgentCronDto) dto;
            return e.runOnce();
        }));
    }
}
