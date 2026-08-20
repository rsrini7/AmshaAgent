package com.amsha.tools.builtin.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.core.memory.tiered.ArchivalMemoryService;
import com.amsha.core.memory.tiered.MemoryWriteGovernance;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.persistence.api.ArchivalChunkDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SuppressWarnings("java:S1192")
class ArchivalMemoryInsertToolTest {

    @Mock
    ArchivalMemoryService archivalMemoryService;

    @Mock
    MemoryWriteGovernance governance;

    @Mock
    TieredMemoryConfig tieredConfig;

    @Mock
    AuditService auditService;

    ArchivalMemoryInsertTool tool;

    private static final ToolExecutionContext CTX =
            new ToolExecutionContext(null, null, null, null, null, null, null, null, null, null, null, null);

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        tool = new ArchivalMemoryInsertTool();
        tool.archivalMemoryService = archivalMemoryService;
        tool.governance = governance;
        tool.tieredConfig = tieredConfig;
        tool.auditService = auditService;
    }

    @Test
    void testRequiresManualApproval_alwaysTrue() {
        // Approval gate must fire regardless of params
        assertTrue(tool.requiresManualApproval(Map.of()));
        assertTrue(tool.requiresManualApproval(Map.of("content", "test", "chunk_type", "FACT")));
        assertTrue(tool.requiresManualApproval(Map.of("content", "x".repeat(4000), "chunk_type", "CODE")));
    }

    @Test
    void testApprovalGate_blocksExecuteAtSupervisedLevel() throws Exception {
        // At SUPERVISED autonomy, the tool's requiresManualApproval()=true
        // causes ToolInvocationGuard to return PauseForApproval.
        // We verify the tool metadata is correct so the guard path is wired:
        // 1. Tool declares requiresManualApproval=true
        // 2. Tool declares non-idempotent
        // 3. Tool declares WRITE_MEMORY operation (LOW risk — memory write, not filesystem)
        assertTrue(tool.requiresManualApproval(Map.of("content", "x", "chunk_type", "FACT")));
        assertEquals(IdempotencyMode.NON_IDEMPOTENT, tool.idempotencyMode());
        assertEquals(ToolOperation.WRITE_MEMORY, tool.requiredOperation());
    }

    @Test
    void testSuccessfulInsert() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(
                        CTX,
                        Map.of(
                                "content", "Always use records for DTOs",
                                "chunk_type", "PATTERN")));

        assertTrue(response.success());
        assertTrue(response.output().contains("Inserted archival chunk"));
        verify(governance).checkArchivalWrite();
        verify(archivalMemoryService).upsert(any(ArchivalChunkDto.class));
    }

    @Test
    void testInsert_withTags() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(
                        CTX,
                        Map.of(
                                "content", "Use Quarkus for microservices",
                                "chunk_type", "PREFERENCE",
                                "tags", "quarkus,java")));

        assertTrue(response.success());
        ArgumentCaptor<ArchivalChunkDto> captor = ArgumentCaptor.forClass(ArchivalChunkDto.class);
        verify(archivalMemoryService).upsert(captor.capture());
        assertEquals("quarkus,java", captor.getValue().tags());
    }

    @Test
    void testInvalidChunkType_returnsError() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(
                        CTX,
                        Map.of(
                                "content", "some content",
                                "chunk_type", "INVALID")));

        assertFalse(response.success());
    }

    @Test
    void testMissingContent_returnsError() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(CTX, Map.of("chunk_type", "FACT")));

        assertFalse(response.success());
    }

    @Test
    void testContentExceedsMax_returnsError() throws Exception {
        String longContent = "x".repeat(4001);

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(CTX, Map.of("content", longContent, "chunk_type", "FACT")));

        assertFalse(response.success());
    }

    @Test
    void testContentAtMax_succeeds() throws Exception {
        String maxContent = "x".repeat(4000);

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(CTX, Map.of("content", maxContent, "chunk_type", "DOC")));

        assertTrue(response.success());
    }

    @Test
    void testRateLimitExceeded_returns429() throws Exception {
        Mockito.doThrow(new MemoryWriteGovernance.RateLimitExceededException("archival", 10, "hour"))
                .when(governance)
                .checkArchivalWrite();

        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(
                        CTX,
                        Map.of(
                                "content", "test",
                                "chunk_type", "FACT")));

        assertFalse(response.success());
    }

    @Test
    void testChunkTypeCaseInsensitive() throws Exception {
        ToolResponse response = WorkspaceContext.run(
                "s1",
                "user1",
                "c1",
                AutonomyLevel.FULL,
                "coordinator",
                () -> tool.execute(
                        CTX,
                        Map.of(
                                "content", "test content",
                                "chunk_type", "fact")));

        assertTrue(response.success());
        ArgumentCaptor<ArchivalChunkDto> captor = ArgumentCaptor.forClass(ArchivalChunkDto.class);
        verify(archivalMemoryService).upsert(captor.capture());
        assertEquals("FACT", captor.getValue().chunkType());
    }
}
