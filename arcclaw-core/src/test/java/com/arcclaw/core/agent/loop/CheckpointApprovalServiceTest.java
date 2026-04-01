package com.arcclaw.core.agent.loop;

import static org.mockito.Mockito.*;

import java.util.Optional;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CheckpointApprovalServiceTest {

    @Mock StatefulAgentStore agentStore;
    @Mock CheckpointResumeHandler resumeHandler;
    @InjectMocks CheckpointApprovalService checkpointApprovalService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void test_approveTool_marksToolApprovedAndResumesWhenAllDecided() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.isAllBatchToolsDecided("s1", "coordinator")).thenReturn(true);

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "github", "APPROVED");
        verify(resumeHandler, times(1)).resumeAfterBatchDecision(eq("s1"), eq("coordinator"), any(), any(), any());
    }

    @Test
    void test_approveTool_doesNotResumeWhenToolsStillPending() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.isAllBatchToolsDecided("s1", "coordinator")).thenReturn(false);

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "github", "APPROVED");
        verify(resumeHandler, never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
    }

    @Test
    void test_approveTool_noOpWhenNoCheckpoint() {
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore, never()).updateBatchToolDecision(any(), any(), any(), any());
        verify(resumeHandler, never()).resumeAfterBatchDecision(any(), any(), any(), any(), any());
    }

    @Test
    void test_approveAll_approvesAllPendingTools() throws Exception {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setPendingToolName("parallel:github,sandboxexec");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandboxexec\":\"PENDING\"}");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        checkpointApprovalService.objectMapper = new ObjectMapper();

        checkpointApprovalService.approveAll("s1", "bulk approve");

        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "github", "APPROVED");
        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "sandboxexec", "APPROVED");
        verify(resumeHandler, times(1)).resumeAfterBatchDecision(eq("s1"), eq("coordinator"), any(), any(), any());
    }
}
