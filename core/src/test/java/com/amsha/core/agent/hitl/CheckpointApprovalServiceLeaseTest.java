package com.amsha.core.agent.hitl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CheckpointApprovalServiceLeaseTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentSseService agentSseService;

    @Mock
    Instance<CheckpointResumeHandler> resumeHandlerInstance;

    @Mock
    ApprovalLeaseStore leaseStore;

    ObjectMapper objectMapper;
    CheckpointApprovalService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();

        service = new CheckpointApprovalService(agentStore, agentSseService, objectMapper, resumeHandlerInstance);
        service.approvalLeaseStore = leaseStore;

        when(agentStore.updateBatchToolDecision(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    void testApproveToolGrantsLeaseForSingleToolObjectParams() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setSessionId("sess-1");
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName("schedule_task");
        cp.setPendingToolParametersJson("{\"cron\":\"* * * * *\",\"goal\":\"write factorial\"}");
        cp.setBatchToolDecisions("{\"schedule_task\":\"PENDING\"}");

        when(agentStore.findLatestAwaitingApproval("sess-1")).thenReturn(Optional.of(cp));

        service.approveTool("sess-1", "schedule_task", "approved by user");

        Map<String, Object> expectedParams = Map.of("cron", "* * * * *", "goal", "write factorial");
        verify(leaseStore).grant("sess-1", "schedule_task", expectedParams);
    }

    @Test
    void testApproveToolGrantsLeaseForBatchArrayParams() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setSessionId("sess-2");
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName("parallel|schedule_task,web_search");
        cp.setPendingToolParametersJson("[{\"toolName\":\"schedule_task\",\"params\":{\"goal\":\"calc\"}},"
                + "{\"toolName\":\"web_search\",\"params\":{\"q\":\"math\"}}]");
        cp.setBatchToolDecisions("{\"schedule_task\":\"PENDING\",\"web_search\":\"PENDING\"}");

        when(agentStore.findLatestAwaitingApproval("sess-2")).thenReturn(Optional.of(cp));

        service.approveTool("sess-2", "schedule_task", "ok");

        verify(leaseStore).grant("sess-2", "schedule_task", Map.of("goal", "calc"));
        verify(leaseStore, never()).grant(eq("sess-2"), eq("web_search"), any());
    }

    @Test
    void testApproveAllGrantsLeaseForAllPendingTools() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setSessionId("sess-3");
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName("parallel|schedule_task,web_search");
        cp.setPendingToolParametersJson("[{\"toolName\":\"schedule_task\",\"params\":{\"goal\":\"calc\"}},"
                + "{\"toolName\":\"web_search\",\"params\":{\"q\":\"math\"}}]");
        cp.setBatchToolDecisions("{\"schedule_task\":\"PENDING\",\"web_search\":\"PENDING\"}");

        when(agentStore.findLatestAwaitingApproval("sess-3")).thenReturn(Optional.of(cp));

        service.approveAll("sess-3", "approved all");

        verify(leaseStore).grant("sess-3", "schedule_task", Map.of("goal", "calc"));
        verify(leaseStore).grant("sess-3", "web_search", Map.of("q", "math"));
    }

    @Test
    void testApproveToolFallbackWhenBatchToolDecisionsMissing() {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setSessionId("sess-4");
        cp.setAgentRole("coordinator");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.setPendingToolName("schedule_task");
        cp.setPendingToolParametersJson("{\"goal\":\"single\"}");
        cp.setBatchToolDecisions(null);

        when(agentStore.findLatestAwaitingApproval("sess-4")).thenReturn(Optional.of(cp));

        service.approveTool("sess-4", "schedule_task", "ok");

        assertNotNull(cp.getBatchToolDecisions());
        verify(leaseStore).grant("sess-4", "schedule_task", Map.of("goal", "single"));
    }
}
