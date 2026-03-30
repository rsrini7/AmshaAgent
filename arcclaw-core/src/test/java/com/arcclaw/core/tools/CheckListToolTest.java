package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.event.Event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;

@ExtendWith(MockitoExtension.class)
class CheckListToolTest {

    @Mock
    Event<CheckListEvent> eventMock;

    @Mock
    AuditService auditService;

    @InjectMocks
    CheckListTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";

    @BeforeEach
    void setup() {
        WorkspaceContext.run(SESSION_ID, USER_ID, "chat-1", null, "architect", () -> {});
    }

    @Test
    void testValidChecklistUpdate() {
        Map<String, Object> params = Map.of("checkList", List.of(
            Map.of("content", "Task 1", "status", "completed", "activeForm", "f1"),
            Map.of("content", "Task 2", "status", "in_progress", "activeForm", "f2")
        ));

        WorkspaceContext.run(SESSION_ID, USER_ID, "chat-1", null, "architect", () -> {
            ToolResponse resp = tool.execute(params);
            assertTrue(resp.success());

            ArgumentCaptor<CheckListEvent> captor = ArgumentCaptor.forClass(CheckListEvent.class);
            verify(eventMock).fire(captor.capture());
            
            assertEquals(SESSION_ID, captor.getValue().sessionId());
            assertEquals(2, captor.getValue().checkList().checkList().size());
            assertEquals(CheckListTool.CheckList.Status.in_progress, captor.getValue().checkList().checkList().get(1).status());
            
            verify(auditService).record(eq(SESSION_ID), eq(USER_ID), eq("architect"), 
                eq(SecurityEventType.TOOL_EXECUTED), any());
        });
    }

    @Test
    void testMultipleInProgressItemsThrows() {
        Map<String, Object> params = Map.of("checkList", List.of(
            Map.of("content", "T1", "status", "in_progress", "activeForm", "f1"),
            Map.of("content", "T2", "status", "in_progress", "activeForm", "f2")
        ));

        WorkspaceContext.run(SESSION_ID, USER_ID, "chat-1", null, "architect", () -> {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> tool.execute(params));
            assertTrue(ex.getMessage().contains("Found: 2"));
        });
    }

    @Test
    void testBlankContentThrows() {
        Map<String, Object> params = Map.of("checkList", List.of(
            Map.of("content", "", "status", "pending", "activeForm", "f1")
        ));

        WorkspaceContext.run(SESSION_ID, USER_ID, "chat-1", null, "architect", () -> {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(params));
        });
    }

    @Test
    void testEmptyChecklistThrows() {
        Map<String, Object> params = Map.of("checkList", List.of());

        WorkspaceContext.run(SESSION_ID, USER_ID, "chat-1", null, "architect", () -> {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(params));
        });
    }

    @Test
    void testMissingUserIdThrowsForbidden() {
         WorkspaceContext.run(SESSION_ID, "", "chat-1", null, "architect", () -> {
            ToolResponse resp = tool.execute(Map.of("checkList", List.of(Map.of("content", "t", "status", "pending", "activeForm", "f"))));
            assertEquals(403, resp.exitCode());
        });
    }
}
