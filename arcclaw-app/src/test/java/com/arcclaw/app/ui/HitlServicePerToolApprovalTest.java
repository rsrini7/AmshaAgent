package com.arcclaw.app.ui;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapperFactory;
import org.jdbi.v3.core.result.ResultIterable;
import org.jdbi.v3.core.statement.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class HitlServicePerToolApprovalTest {

    @Mock
    Jdbi jdbi;

    @Mock
    Handle handle;

    @Mock
    Query query;

    @Mock
    CheckpointApprovalService checkpointApprovalService;

    @Mock
    AgentOtelContext otelContext;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @InjectMocks
    HitlService hitlService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);

        // Stub withHandle
        when(jdbi.withHandle(any())).thenAnswer(invocation -> {
            HandleCallback<?, Exception> callback = invocation.getArgument(0);
            return callback.withHandle(handle);
        });

        when(handle.createQuery(anyString())).thenReturn(query);
        when(query.bind(anyString(), any(Object.class))).thenReturn(query);
        when(query.bind(anyString(), any(Long.class))).thenReturn(query);
        when(query.registerRowMapper(any(RowMapperFactory.class))).thenReturn(query);
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_resolve_perToolApprove_delegatesCorrectly() {
        CheckpointDto cp = new CheckpointDto(
                1L,
                "s1",
                "default",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);

        ResultIterable<CheckpointDto> resultIterable = mock(ResultIterable.class);
        when(query.mapTo(CheckpointDto.class)).thenReturn(resultIterable);
        when(resultIterable.findOne()).thenReturn(Optional.of(cp));

        hitlService.resolve("1", ToolCapability.GITHUB.toolName(), true, "approved github");

        verify(checkpointApprovalService).approveTool("s1", ToolCapability.GITHUB.toolName(), "approved github");
        verify(checkpointApprovalService, never()).approveAll(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_resolve_perToolDeny_delegatesCorrectly() {
        CheckpointDto cp = new CheckpointDto(
                1L,
                "s1",
                "default",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);

        ResultIterable<CheckpointDto> resultIterable = mock(ResultIterable.class);
        when(query.mapTo(CheckpointDto.class)).thenReturn(resultIterable);
        when(resultIterable.findOne()).thenReturn(Optional.of(cp));

        hitlService.resolve("1", "sandboxexec", false, "unsafe");

        verify(checkpointApprovalService).denyTool("s1", "sandboxexec", "unsafe");
        verify(checkpointApprovalService, never()).denyAll(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_resolve_bulkApprove_stillWorks() {
        CheckpointDto cp = new CheckpointDto(
                1L,
                "s1",
                "default",
                CheckpointStatus.AWAITING_APPROVAL.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null);

        ResultIterable<CheckpointDto> resultIterable = mock(ResultIterable.class);
        when(query.mapTo(CheckpointDto.class)).thenReturn(resultIterable);
        when(resultIterable.findOne()).thenReturn(Optional.of(cp));

        hitlService.resolve("1", null, true, "bulk");

        verify(checkpointApprovalService).approveAll("s1", "bulk");
    }
}
