package com.arcclaw.app.ui;

import static org.mockito.Mockito.*;

import java.util.Optional;

import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
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

public class HitlServiceAbstractionTest {

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
    void test_resolve_approve_callsApproveAllNotReflex() {
        CheckpointDto cp = new CheckpointDto(
                1L, "s1", "default", CheckpointStatus.AWAITING_APPROVAL.name(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, 0, null, null, null, null, null, null, null, 0, null, null);

        ResultIterable<CheckpointDto> resultIterable = mock(ResultIterable.class);
        when(query.mapTo(CheckpointDto.class)).thenReturn(resultIterable);
        when(resultIterable.findOne()).thenReturn(Optional.of(cp));

        hitlService.resolve("1", null, true, "reason");

        verify(checkpointApprovalService).approveAll("s1", "reason");
    }

    @Test
    @SuppressWarnings("unchecked")
    void test_resolve_deny_callsDenyAllNotReflex() {
        CheckpointDto cp = new CheckpointDto(
                1L, "s1", "default", CheckpointStatus.AWAITING_APPROVAL.name(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, 0, null, null, null, null, null, null, null, 0, null, null);

        ResultIterable<CheckpointDto> resultIterable = mock(ResultIterable.class);
        when(query.mapTo(CheckpointDto.class)).thenReturn(resultIterable);
        when(resultIterable.findOne()).thenReturn(Optional.of(cp));

        hitlService.resolve("1", null, false, "reason");

        verify(checkpointApprovalService).denyAll("s1", "reason");
    }
}
