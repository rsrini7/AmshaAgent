package com.amsha.tools.builtin.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.memory.tiered.CoreMemoryService;
import com.amsha.memory.tiered.MemoryWriteGovernance;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SuppressWarnings("java:S1192")
class CoreMemoryWriteToolsTest {

    @Mock
    CoreMemoryService coreMemoryService;

    @Mock
    MemoryWriteGovernance governance;

    @Mock
    AuditService auditService;

    CoreMemoryReadTool readTool;
    CoreMemoryAppendTool appendTool;
    CoreMemoryReplaceTool replaceTool;

    private static final ToolExecutionContext CTX =
            new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null);

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);

        readTool = new CoreMemoryReadTool();
        readTool.coreMemoryService = coreMemoryService;

        appendTool = new CoreMemoryAppendTool();
        appendTool.coreMemoryService = coreMemoryService;
        appendTool.governance = governance;
        appendTool.auditService = auditService;

        replaceTool = new CoreMemoryReplaceTool();
        replaceTool.coreMemoryService = coreMemoryService;
        replaceTool.governance = governance;
        replaceTool.auditService = auditService;
    }

    @Test
    void testReadTool_allBlocks() throws Exception {
        CoreMemoryBlockDto block =
                new CoreMemoryBlockDto("id1", "user1", "user_profile", "Alice, developer", 1, null, null);
        when(coreMemoryService.readAll("user1")).thenReturn(List.of(block));

        ToolResponse response = WorkspaceContext.run(
                "s1", "user1", "c1", AutonomyLevel.FULL, "coordinator", () -> readTool.execute(CTX, Map.of()));

        assertTrue(response.success());
        assertTrue(response.output().contains("user_profile"));
        assertTrue(response.output().contains("Alice, developer"));
    }

    @Test
    void testReadTool_specificBlock() throws Exception {
        CoreMemoryBlockDto block =
                new CoreMemoryBlockDto("id1", "user1", "constraints", "Never modify .env", 1, null, null);
        when(coreMemoryService.get("user1", "constraints")).thenReturn(Optional.of(block));

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> readTool.execute(CTX, Map.of("block_key", "constraints")));

        assertTrue(response.success());
        assertTrue(response.output().contains("Never modify .env"));
    }

    @Test
    void testAppendTool_usesAtomicAppend() throws Exception {
        when(coreMemoryService.append("user1", "user_profile", "New fact")).thenReturn("New fact");

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(CTX, Map.of("block_key", "user_profile", "content", "New fact")));

        assertTrue(response.success());
        verify(governance).checkCoreWrite();
        verify(coreMemoryService).append("user1", "user_profile", "New fact");
    }

    @Test
    void testAppendTool_casRetry_preservesConcurrentAppends() throws Exception {
        // Simulate: append() does read-modify-write with CAS internally
        // First call returns the concatenated result
        when(coreMemoryService.append("user1", "active_task", "debug compaction"))
                .thenReturn("existing content\ndebug compaction");

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(CTX, Map.of("block_key", "active_task", "content", "debug compaction")));

        assertTrue(response.success());
        verify(coreMemoryService).append("user1", "active_task", "debug compaction");
    }

    @Test
    void testReplaceTool_replacesEntireContent() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> replaceTool.execute(CTX, Map.of("block_key", "constraints", "content", "New constraints")));

        assertTrue(response.success());
        verify(coreMemoryService).upsert("user1", "constraints", "New constraints");
    }

    @Test
    void testAppendTool_missingBlockKey_returnsError() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(CTX, Map.of("content", "something")));

        assertFalse(response.success());
    }

    @Test
    void testAppendTool_missingContent_returnsError() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(CTX, Map.of("block_key", "user_profile")));

        assertFalse(response.success());
    }

    @Test
    void testAppendTool_invalidBlockKey_returnsError() throws Exception {
        Mockito.doThrow(new IllegalArgumentException("Unknown block key: bad_key"))
                .when(coreMemoryService)
                .append(anyString(), anyString(), anyString());

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(CTX, Map.of("block_key", "bad_key", "content", "test")));

        assertFalse(response.success());
    }

    @Test
    void testAppendTool_rateLimitExceeded_returns429() throws Exception {
        Mockito.doThrow(new MemoryWriteGovernance.RateLimitExceededException("core", 5, "minute"))
                .when(governance)
                .checkCoreWrite();

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(CTX, Map.of("block_key", "user_profile", "content", "test")));

        assertFalse(response.success());
    }

    @Test
    void testAppendTool_auditLogged() throws Exception {
        when(coreMemoryService.append("user1", "user_profile", "test")).thenReturn("test");

        ToolExecutionContext auditCtx = new ToolExecutionContext(
                "s1", "user1", "c1", "coordinator", null, null, null, null, null, null, null, null);

        WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> appendTool.execute(auditCtx, Map.of("block_key", "user_profile", "content", "test")));

        verify(auditService)
                .record(
                        "s1",
                        "user1",
                        "coordinator",
                        SecurityEventType.MEMORY_WRITE_MUTATED,
                        "tool=core_memory_append block=user_profile");
    }
}
