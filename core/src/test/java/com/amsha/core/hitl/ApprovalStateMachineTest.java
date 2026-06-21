package com.amsha.core.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalDecisionEvent;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.channel.api.hitl.ApprovalResult;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.state.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalStateMachineTest {

    private ApprovalStateMachine stateMachine;
    private StatefulAgentStore agentStore;
    private DomainEventBus eventBus;
    private CheckpointResumeHandler resumeHandler;
    private ObjectMapper objectMapper;

    private AgentCheckpoint cp;

    @BeforeEach
    void setup() throws Exception {
        agentStore = mock(StatefulAgentStore.class);
        eventBus = mock(DomainEventBus.class);
        resumeHandler = mock(CheckpointResumeHandler.class);
        objectMapper = new ObjectMapper();

        stateMachine = new ApprovalStateMachine();
        stateMachine.agentStore = agentStore;
        stateMachine.eventBus = eventBus;
        stateMachine.resumeHandler = resumeHandler;
        stateMachine.objectMapper = objectMapper;
        stateMachine.hitlTimeoutHours = 24L;

        cp = new AgentCheckpointRecord();
        cp.setSessionId("session-123");
        cp.setAgentRole("coder");
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        when(agentStore.findLatestAwaitingApproval("session-123")).thenReturn(Optional.of(cp));
        when(agentStore.isCheckpointExpired("session-123", 24L)).thenReturn(false);

        doAnswer(invocation -> {
                    String tool = invocation.getArgument(2);
                    String decision = invocation.getArgument(3);
                    try {
                        Map<String, ToolDecision> map = objectMapper.readValue(
                                cp.getBatchToolDecisions(), new TypeReference<Map<String, ToolDecision>>() {});
                        map.put(tool, ToolDecision.valueOf(decision));
                        cp.setBatchToolDecisions(objectMapper.writeValueAsString(map));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return true;
                })
                .when(agentStore)
                .updateBatchToolDecision(anyString(), anyString(), anyString(), anyString());
    }

    private void mockTools(List<String> pending, List<String> all) {
        // Just mock BatchDecisionUtil via JSON to avoid static mocking
        // Create a JSON representing the tool decisions
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < all.size(); i++) {
            String tool = all.get(i);
            String dec = pending.contains(tool) ? ToolDecision.PENDING.name() : ToolDecision.APPROVED.name();
            sb.append("\"").append(tool).append("\":\"").append(dec).append("\"");
            if (i < all.size() - 1) sb.append(",");
        }
        sb.append("}");
        cp.setBatchToolDecisions(sb.toString());
    }

    @Test
    void testApprove_singleTool_approved() {
        mockTools(List.of("github:read"), List.of("github:read"));
        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);

        ApprovalResult result = stateMachine.decide("session-123", "github:read", ApprovalDecision.APPROVE, "test");

        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        assertEquals("github:read", result.toolName());
        assertEquals(0, result.remainingPendingTools());

        verify(agentStore).updateBatchToolDecision("session-123", "coder", "github:read", ToolDecision.APPROVED.name());
        verify(resumeHandler).resumeAfterBatchDecision(any(), any(), any(), any(), any());
        verify(eventBus).publish(eq(EventTypeCatalog.APPROVAL_DECISION), any(ApprovalDecisionEvent.class));
    }

    @Test
    void testReject_singleTool_rejected() {
        mockTools(List.of("github:read"), List.of("github:read"));
        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);

        ApprovalResult result = stateMachine.decide("session-123", "github:read", ApprovalDecision.REJECT, "test");

        assertEquals(ApprovalOutcome.REJECTED, result.outcome());
        verify(agentStore).updateBatchToolDecision("session-123", "coder", "github:read", ToolDecision.DENIED.name());
    }

    @Test
    void testExpired() {
        when(agentStore.isCheckpointExpired("session-123", 24L)).thenReturn(true);
        ApprovalResult result = stateMachine.decide("session-123", "github:read", ApprovalDecision.APPROVE, "test");
        assertEquals(ApprovalOutcome.EXPIRED, result.outcome());
    }

    @Test
    void testNotFound_noCheckpoint() {
        when(agentStore.findLatestAwaitingApproval("missing")).thenReturn(Optional.empty());
        ApprovalResult result = stateMachine.decide("missing", "github:read", ApprovalDecision.APPROVE, "test");
        assertEquals(ApprovalOutcome.NOT_FOUND, result.outcome());
    }

    @Test
    void testPartial_stillPending() {
        mockTools(List.of("tool1", "tool2", "tool3"), List.of("tool1", "tool2", "tool3"));
        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(false);

        ApprovalResult result = stateMachine.decide("session-123", "tool1", ApprovalDecision.APPROVE, "test");

        assertEquals(ApprovalOutcome.PARTIAL_STILL_PENDING, result.outcome());
        assertEquals(2, result.remainingPendingTools());
    }

    @Test
    void testMixedBatch_atLeastOneApproval_approvedOutcome() {
        // Batch: tool1, tool2.
        // tool1 already DENIED.
        // Now approving tool2.
        mockTools(List.of("tool2"), List.of("tool1", "tool2"));
        // Manually set tool1 to DENIED in the JSON
        cp.setBatchToolDecisions("{\"tool1\":\"DENIED\",\"tool2\":\"PENDING\"}");

        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);

        ApprovalResult result = stateMachine.decide("session-123", "tool2", ApprovalDecision.APPROVE, "test");

        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        verify(resumeHandler).resumeAfterBatchDecision(any(), any(), any(), any(), any());
    }

    @Test
    void testMixedBatch_allDenied_rejectedOutcome() {
        // Batch: tool1, tool2.
        // tool1 already DENIED.
        // Now denying tool2.
        mockTools(List.of("tool2"), List.of("tool1", "tool2"));
        cp.setBatchToolDecisions("{\"tool1\":\"DENIED\",\"tool2\":\"PENDING\"}");

        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);

        ApprovalResult result = stateMachine.decide("session-123", "tool2", ApprovalDecision.REJECT, "test");

        assertEquals(ApprovalOutcome.REJECTED, result.outcome());
    }

    @Test
    void testDuplicateApproval_alreadyDecided() {
        // tool1 is already APPROVED (not in pending list)
        mockTools(List.of("tool2"), List.of("tool1", "tool2"));

        ApprovalResult result = stateMachine.decide("session-123", "tool1", ApprovalDecision.APPROVE, "test");
        assertEquals(ApprovalOutcome.ALREADY_DECIDED, result.outcome());
    }
}
