package com.amsha.core.agent.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Optional;

import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CheckpointApprovalTraceTest {

    private final TracePublisher tracePublisher = mock(TracePublisher.class);
    private final ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);

    private CheckpointApprovalService service;
    private StatefulAgentStore agentStore;

    @BeforeEach
    void setup() {
        agentStore = mock(StatefulAgentStore.class);
        AgentSseService agentSseService = mock(AgentSseService.class);
        var resumeHandlerInstance = mock(Instance.class);
        when(resumeHandlerInstance.isResolvable()).thenReturn(false);
        service = new CheckpointApprovalService(
                agentStore, agentSseService, new ObjectMapper(), resumeHandlerInstance, tracePublisher);
    }

    @Test
    void approvingEmitsHitlResolvedApproved() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setPendingToolName("github");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.updateBatchToolDecision(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.approveTool("s1", ToolCapability.GITHUB.toolName(), "test");

        verify(tracePublisher).publish(captor.capture());
        assertEquals("APPROVED", captor.getValue().status());
        assertEquals(TraceStage.HITL_RESOLVED, captor.getValue().stage());
        assertEquals("github", captor.getValue().detail());
    }

    @Test
    void denyingEmitsHitlResolvedDenied() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setPendingToolName("github");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        when(agentStore.load("s1", "coordinator")).thenReturn(Optional.of(cp));

        service.deny("s1", "coordinator", "not allowed");

        verify(tracePublisher).publish(captor.capture());
        assertEquals("DENIED", captor.getValue().status());
        assertEquals(TraceStage.HITL_RESOLVED, captor.getValue().stage());
        assertEquals("github", captor.getValue().detail());
    }
}
