package com.amsha.core.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.amsha.core.audit.AuditService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
    void failedAtomicDecision_doesNotPublishOrResume() {
        mockTools(List.of("host_exec"), List.of("host_exec"));
        when(agentStore.updateBatchToolDecision("session-123", "coder", "host_exec", ToolDecision.APPROVED.name()))
                .thenReturn(false);

        ApprovalResult result =
                stateMachine.decide("session-123", "host_exec", ApprovalDecision.APPROVE, "stale approval");

        assertEquals(ApprovalOutcome.ALREADY_DECIDED, result.outcome());
        verify(eventBus, Mockito.never()).publish(any(), any());
        verify(resumeHandler, Mockito.never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
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

    @Test
    void testCombinedBatchToolName_withUnderscores_resolvesToApproveAll() {
        mockTools(List.of("core_memory_read", "host_exec"), List.of("core_memory_read", "host_exec"));
        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);

        ApprovalResult result =
                stateMachine.decide("session-123", "core_memory_read_host_exec", ApprovalDecision.APPROVE, "test");

        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        verify(agentStore, times(2))
                .updateBatchToolDecision(eq("session-123"), eq("coder"), anyString(), eq(ToolDecision.APPROVED.name()));
        verify(resumeHandler).resumeAfterBatchDecision(any(), any(), any(), any(), any());
    }

    @Test
    void approvalGrantsLeaseForSingleToolCheckpointShape() {
        // ADR-approval-lease-for-parallel-batches: an approved single call records a lease.
        mockTools(List.of("github:read"), List.of("github:read"));
        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);
        cp.setPendingToolParametersJson("{\"owner\":\"octocat\",\"repo\":\"amsha\"}");
        stateMachine.approvalLeaseStore = new ApprovalLeaseStore();
        stateMachine.approvalLeaseStore.objectMapper = objectMapper;
        stateMachine.approvalLeaseStore.auditService = Mockito.mock(AuditService.class);
        stateMachine.approvalLeaseStore.enabled = true;
        stateMachine.approvalLeaseStore.ttlSeconds = 3600;
        stateMachine.approvalLeaseStore.init();

        stateMachine.decide("session-123", "github:read", ApprovalDecision.APPROVE, "test");

        assertTrue(stateMachine.approvalLeaseStore.hasActiveLease(
                "session-123", "coder", null, "github:read", Map.of("owner", "octocat", "repo", "amsha")));
    }

    @Test
    void batchApprovalGrantsLeaseOnlyForTheApprovedToolParams() {
        // Batch checkpoint params are a JSON array of {toolName, params} entries; the
        // lease must bind to the approved tool's exact params only.
        mockTools(List.of("host_exec"), List.of("host_exec"));
        when(agentStore.isAllBatchToolsDecided("session-123", "coder")).thenReturn(true);
        cp.setPendingToolParametersJson(
                "[{\"callId\":\"call_0\",\"toolName\":\"recall_memory_search\",\"params\":{\"query\":\"x\"}},"
                        + "{\"callId\":\"call_1\",\"toolName\":\"host_exec\",\"params\":{\"command\":\"ls\"}}]");
        stateMachine.approvalLeaseStore = new ApprovalLeaseStore();
        stateMachine.approvalLeaseStore.objectMapper = objectMapper;
        stateMachine.approvalLeaseStore.auditService = Mockito.mock(AuditService.class);
        stateMachine.approvalLeaseStore.enabled = true;
        stateMachine.approvalLeaseStore.ttlSeconds = 3600;
        stateMachine.approvalLeaseStore.init();

        stateMachine.decide("session-123", "host_exec", ApprovalDecision.APPROVE, "test");

        assertTrue(stateMachine.approvalLeaseStore.hasActiveLease(
                "session-123", "coder", null, "host_exec", Map.of("command", "ls")));
        assertFalse(stateMachine.approvalLeaseStore.hasActiveLease(
                "session-123", "coder", null, "recall_memory_search", Map.of("query", "x")));
    }

    @Test
    void denialDoesNotGrantAnyLease() {
        mockTools(List.of("host_exec"), List.of("host_exec"));
        cp.setPendingToolParametersJson("{\"command\":\"ls\"}");
        stateMachine.approvalLeaseStore = new ApprovalLeaseStore();
        stateMachine.approvalLeaseStore.objectMapper = objectMapper;
        stateMachine.approvalLeaseStore.auditService = Mockito.mock(AuditService.class);
        stateMachine.approvalLeaseStore.enabled = true;
        stateMachine.approvalLeaseStore.ttlSeconds = 3600;
        stateMachine.approvalLeaseStore.init();

        stateMachine.decide("session-123", "host_exec", ApprovalDecision.REJECT, "test");

        assertFalse(stateMachine.approvalLeaseStore.hasActiveLease(
                "session-123", "coder", null, "host_exec", Map.of("command", "ls")));
        assertEquals(0, stateMachine.approvalLeaseStore.size());
    }

    @Test
    void decide_whenSessionContainsDelegatedChild_resolvesAndAppliesToChildCheckpoint() {
        String parentSessionId = "cron-123";
        String childSessionId = "cron-123:delegate:brahma:456";
        cp.setSessionId(childSessionId);
        mockTools(List.of("sandbox_exec"), List.of("sandbox_exec"));
        when(agentStore.findLatestAwaitingApproval(parentSessionId)).thenReturn(Optional.empty());
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(parentSessionId))
                .thenReturn(Optional.of(cp));
        when(agentStore.findLatestAwaitingApproval(childSessionId)).thenReturn(Optional.of(cp));
        when(agentStore.isCheckpointExpired(childSessionId, 24)).thenReturn(false);
        when(agentStore.isAllBatchToolsDecided(childSessionId, "coder")).thenReturn(true);

        ApprovalResult result = stateMachine.decide(parentSessionId, "sandbox_exec", ApprovalDecision.APPROVE, "ok");

        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        assertEquals(childSessionId, result.sessionId());
        assertEquals("sandbox_exec", result.toolName());
        verify(resumeHandler).resumeAfterBatchDecision(eq(childSessionId), eq("coder"), any(), any(), eq("websocket"));
    }

    @Test
    void decide_whenParentCheckpointAwaitingGaruda_resolvesChildCheckpointForSandboxExec() {
        String parentSessionId = "cron-123";
        String childSessionId = "cron-123:delegate:brahma:456";

        AgentCheckpoint parentCp = new AgentCheckpointRecord();
        parentCp.setId(10L);
        parentCp.setSessionId(parentSessionId);
        parentCp.setAgentRole("coordinator");
        parentCp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        parentCp.setPendingToolName("garuda");
        parentCp.setBatchToolDecisions("{\"garuda\":\"PENDING\"}");

        cp.setSessionId(childSessionId);
        cp.setAgentRole("coder");
        mockTools(List.of("sandbox_exec"), List.of("sandbox_exec"));

        when(agentStore.findLatestAwaitingApproval(parentSessionId)).thenReturn(Optional.of(parentCp));
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(parentSessionId))
                .thenReturn(Optional.of(cp));
        when(agentStore.findLatestAwaitingApproval(childSessionId)).thenReturn(Optional.of(cp));
        when(agentStore.isCheckpointExpired(childSessionId, 24)).thenReturn(false);
        when(agentStore.isAllBatchToolsDecided(childSessionId, "coder")).thenReturn(true);

        ApprovalResult result = stateMachine.decide(parentSessionId, "sandbox_exec", ApprovalDecision.APPROVE, "ok");

        assertEquals(ApprovalOutcome.APPROVED, result.outcome());
        assertEquals(childSessionId, result.sessionId());
        assertEquals("sandbox_exec", result.toolName());
        verify(resumeHandler).resumeAfterBatchDecision(eq(childSessionId), eq("coder"), any(), any(), eq("websocket"));
    }

    @Test
    void decide_whenParentCheckpointAwaitingGaruda_rejectsChildCheckpointForSandboxExec() {
        String parentSessionId = "cron-123";
        String childSessionId = "cron-123:delegate:brahma:456";

        AgentCheckpoint parentCp = new AgentCheckpointRecord();
        parentCp.setId(10L);
        parentCp.setSessionId(parentSessionId);
        parentCp.setAgentRole("coordinator");
        parentCp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        parentCp.setPendingToolName("garuda");
        parentCp.setBatchToolDecisions("{\"garuda\":\"PENDING\"}");

        cp.setSessionId(childSessionId);
        cp.setAgentRole("coder");
        cp.setBatchToolDecisions("{\"sandbox_exec\":\"DENIED\"}");
        mockTools(List.of("sandbox_exec"), List.of("sandbox_exec"));

        when(agentStore.findLatestAwaitingApproval(parentSessionId)).thenReturn(Optional.of(parentCp));
        when(agentStore.findLatestAwaitingApprovalBySessionContaining(parentSessionId))
                .thenReturn(Optional.of(cp));
        when(agentStore.findLatestAwaitingApproval(childSessionId)).thenReturn(Optional.of(cp));
        when(agentStore.isCheckpointExpired(childSessionId, 24)).thenReturn(false);
        when(agentStore.isAllBatchToolsDecided(childSessionId, "coder")).thenReturn(true);

        ApprovalResult result =
                stateMachine.decide(parentSessionId, "sandbox_exec", ApprovalDecision.REJECT, "user rejected");

        assertEquals(ApprovalOutcome.REJECTED, result.outcome());
        assertEquals(childSessionId, result.sessionId());
        assertEquals("sandbox_exec", result.toolName());
        verify(resumeHandler).resumeAfterBatchDecision(eq(childSessionId), eq("coder"), any(), any(), eq("websocket"));
    }
}
