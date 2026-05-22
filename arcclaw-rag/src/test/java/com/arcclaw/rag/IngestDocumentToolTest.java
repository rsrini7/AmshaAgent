package com.arcclaw.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.reflex.PiiScanReflex;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.persistence.api.IngestResult;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestDocumentToolTest {

    @Mock
    MemoryRetrievalPort memoryPort;

    @Mock
    AuditService auditService;

    @Mock
    PiiScanReflex piiReflex;

    @InjectMocks
    IngestDocumentTool tool;

    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        context = new ToolExecutionContext(
            "session-123",
            "user-456",
            "chat-789",
            "coordinator",
            AutonomyLevel.SUPERVISED,
            "trace-abc",
            "telegram",
            "platform-123",
            "RAW_USER_GOAL",
            null,
            null,
            null
        );
    }

    @Test
    void testExecuteWithTextParameter() {
        Map<String, Object> params = Map.of("text", "USER_PHONE=9884691847 and hobby is walking");
        when(memoryPort.index(any(MemoryEntryDto.class)))
                .thenReturn(new IngestResult("chunk-1", false));

        ToolResponse response = tool.execute(context, params);

        assertTrue(response.success());
        assertEquals("Document ingested successfully into Global KB. chunkId: chunk-1", response.output());
        verify(memoryPort).index(any(MemoryEntryDto.class));
    }

    @Test
    void testExecuteWithDocumentParameter() {
        Map<String, Object> params = Map.of("document", "USER_PHONE=9884691847 and hobby is walking");
        when(memoryPort.index(any(MemoryEntryDto.class)))
                .thenReturn(new IngestResult("chunk-1", false));

        ToolResponse response = tool.execute(context, params);

        assertTrue(response.success());
        assertEquals("Document ingested successfully into Global KB. chunkId: chunk-1", response.output());
        verify(memoryPort).index(any(MemoryEntryDto.class));
    }

    @Test
    void testExecuteWithContentParameter() {
        Map<String, Object> params = Map.of("content", "USER_PHONE=9884691847 and hobby is walking");
        when(memoryPort.index(any(MemoryEntryDto.class)))
                .thenReturn(new IngestResult("chunk-1", false));

        ToolResponse response = tool.execute(context, params);

        assertTrue(response.success());
        assertEquals("Document ingested successfully into Global KB. chunkId: chunk-1", response.output());
    }

    @Test
    void testExecuteWithMissingParameters() {
        Map<String, Object> params = new HashMap<>();

        ToolResponse response = tool.execute(context, params);

        assertEquals(400, response.exitCode());
        assertEquals("text parameter is required.", response.output());
    }
}
