package com.arcclaw.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CheckpointApprovalServiceTest {

    @Mock StatefulAgentStore agentStore;
    @Mock CheckpointResumeHandler resumeHandler;
    @Mock AsyncPersistenceEngine asyncEngine;
    @Mock AgentSseService agentSseService;
    @InjectMocks CheckpointApprovalService checkpointApprovalService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        // persistAndWait mock removed: method deleted in Tier 3 refactor.
    }

    @Test
    void test_approveTool_marksToolApprovedAndResumesWhenAllDecided() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.isAllBatchToolsDecided("s1", "coordinator")).thenReturn(true);

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "github", ToolDecision.APPROVED.name());
        // resumeHandler verification removed: approveTool now returns CP, triggerResume handles activation.
    }

    @Test
    void test_approveTool_doesNotResumeWhenToolsStillPending() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.isAllBatchToolsDecided("s1", "coordinator")).thenReturn(false);

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "github", ToolDecision.APPROVED.name());
        verify(resumeHandler, never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
    }

    @Test
    void test_approveTool_throwsWhenNoCheckpoint() {
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

        Assertions.assertThrows(
            CheckpointResolutionException.class, 
            () -> checkpointApprovalService.approveTool("s1", "github", "test")
        );

        verify(agentStore, never()).updateBatchToolDecision(any(), any(), any(), any());
        verify(resumeHandler, never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
    }

    @Test
    void test_approveAll_approvesAllPendingTools() throws Exception {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setPendingToolName("parallel|github,sandboxexec");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandboxexec\":\"PENDING\"}");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        checkpointApprovalService.objectMapper = new ObjectMapper();

        checkpointApprovalService.approveAll("s1", "bulk approve");

        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "github", ToolDecision.APPROVED.name());
        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "sandboxexec", ToolDecision.APPROVED.name());
    }

    @Test
    void test_triggerResume_callsResumeHandler() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        when(agentStore.findLatestNonArchivedCheckpoint("s1")).thenReturn(Optional.of(cp));
        when(agentStore.claimForResume(anyString(), anyString())).thenReturn(true);
        
        checkpointApprovalService.triggerResume(cp);
        verify(resumeHandler).resumeAfterBatchDecision(eq("s1"), eq("coordinator"), any(), any(), any());
    }
}
