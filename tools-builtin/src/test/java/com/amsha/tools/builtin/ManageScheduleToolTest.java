package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManageScheduleToolTest {

    @Mock
    CronStorePort cronStore;

    @Mock
    AuditService auditService;

    @InjectMocks
    ManageScheduleTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";

    @Test
    void executeListReturnsFormattedJobs() {
        AgentCronDto dto = new AgentCronDto(
                "cron-1",
                SESSION_ID,
                USER_ID,
                "chat-1",
                "TELEGRAM",
                "platform1",
                "0 9 * * *",
                "UTC",
                false,
                "Send daily report",
                Instant.parse("2026-06-01T09:00:00Z"),
                null,
                "ACTIVE",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());

        when(cronStore.findByUserId(USER_ID)).thenReturn(List.of(dto));

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "list"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("cron-1"));
        assertTrue(resp.output().contains("Send daily report"));
    }

    @Test
    void executeListReturnsNoScheduleFoundWhenEmpty() {
        when(cronStore.findByUserId(USER_ID)).thenReturn(List.of());

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "list"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("No scheduled tasks found"));
    }

    @Test
    void executeDeleteRemovesJobAndAudits() {
        when(cronStore.deleteByIdAndUserId("cron-1", USER_ID)).thenReturn(1);

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "delete", "cronId", "cron-1"));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("deleted"));
        verify(auditService).record(eq(SESSION_ID), eq(USER_ID), anyString(), any(), contains("cron-1"));
    }

    @Test
    void executeDeleteReturnsFailureWhenNotFound() {
        when(cronStore.deleteByIdAndUserId("nonexistent", USER_ID)).thenReturn(0);

        ToolResponse resp = tool.execute(ctx(), Map.of("action", "delete", "cronId", "nonexistent"));

        assertFalse(resp.success());
        assertEquals(404, resp.exitCode());
        verify(auditService, never()).record(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void executeDeleteReturnsFailureWhenCronIdMissing() {
        ToolResponse resp = tool.execute(ctx(), Map.of("action", "delete"));

        assertFalse(resp.success());
        assertEquals(400, resp.exitCode());
        assertTrue(resp.output().contains("cronId is required"));
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                "chat-1",
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
