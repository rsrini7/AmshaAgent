package com.amsha.tools.builtin.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.tiered.CoreMemoryService;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreMemoryReadToolTest {

    @Mock
    CoreMemoryService coreMemoryService;

    @InjectMocks
    CoreMemoryReadTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";

    @Test
    void executeReturnsAllBlocks() throws Exception {
        CoreMemoryBlockDto block1 = new CoreMemoryBlockDto(
                "id-1", USER_ID, "user_profile", "Alice, senior dev", 1, Instant.now(), Instant.now());
        CoreMemoryBlockDto block2 =
                new CoreMemoryBlockDto("id-2", USER_ID, "active_task", "Fix auth bug", 2, Instant.now(), Instant.now());

        when(coreMemoryService.readAll(USER_ID)).thenReturn(List.of(block1, block2));

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID, USER_ID, "chat-1", AutonomyLevel.FULL, "coordinator", () -> tool.execute(ctx(), Map.of()));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("user_profile"));
        assertTrue(resp.output().contains("Alice, senior dev"));
        assertTrue(resp.output().contains("active_task"));
    }

    @Test
    void executeReturnsSpecificBlock() throws Exception {
        CoreMemoryBlockDto block = new CoreMemoryBlockDto(
                "id-1", USER_ID, "preferences", "dark mode, vim keybindings", 3, Instant.now(), Instant.now());

        when(coreMemoryService.get(USER_ID, "preferences")).thenReturn(Optional.of(block));

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(ctx(), Map.of("block_key", "preferences")));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("preferences"));
        assertTrue(resp.output().contains("dark mode"));
        assertTrue(resp.output().contains("v3"));
    }

    @Test
    void executeReturnsNotFoundWhenBlockMissing() throws Exception {
        when(coreMemoryService.get(USER_ID, "nonexistent")).thenReturn(Optional.empty());

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID,
                USER_ID,
                "chat-1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(ctx(), Map.of("block_key", "nonexistent")));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("No block found for key: nonexistent"));
    }

    @Test
    void executeReturnsEmptyMessageWhenNoBlocks() throws Exception {
        when(coreMemoryService.readAll(USER_ID)).thenReturn(List.of());

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID, USER_ID, "chat-1", AutonomyLevel.FULL, "coordinator", () -> tool.execute(ctx(), Map.of()));

        assertTrue(resp.success());
        assertTrue(resp.output().contains("No core memory blocks found"));
    }

    @Test
    void executeHandlesExceptionGracefully() throws Exception {
        when(coreMemoryService.readAll(USER_ID)).thenThrow(new RuntimeException("DB down"));

        ToolResponse resp = WorkspaceContext.run(
                SESSION_ID, USER_ID, "chat-1", AutonomyLevel.FULL, "coordinator", () -> tool.execute(ctx(), Map.of()));

        assertFalse(resp.success());
        assertEquals(1, resp.exitCode());
        assertTrue(resp.output().contains("Core memory read error"));
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
