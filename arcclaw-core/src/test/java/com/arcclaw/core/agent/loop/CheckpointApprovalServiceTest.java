package com.arcclaw.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.state.AgentCheckpointEntity;
import com.arcclaw.core.state.StatefulAgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CheckpointApprovalServiceTest {

    @Mock StatefulAgentStore agentStore;
    @Mock CheckpointResumeHandler resumeHandler;
    @Mock Instance<CheckpointResumeHandler> resumeHandlerInstance;
    @Mock AsyncPersistenceEngine asyncEngine;
    @Mock AgentSseService agentSseService;
    CheckpointApprovalService checkpointApprovalService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        checkpointApprovalService = new CheckpointApprovalService(
                agentStore,
                agentSseService,
                new ObjectMapper(),
                resumeHandlerInstance
        );
        when(resumeHandlerInstance.isResolvable()).thenReturn(true);
        when(resumeHandlerInstance.get()).thenReturn(resumeHandler);
    }

    @Test
    void test_approveTool_marksToolApprovedAndResumesWhenAllDecided() {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.isAllBatchToolsDecided("s1", "coordinator")).thenReturn(true);

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore).save(any(AgentCheckpointEntity.class));
    }

    @Test
    void test_approveTool_doesNotResumeWhenToolsStillPending() {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.isAllBatchToolsDecided("s1", "coordinator")).thenReturn(false);

        checkpointApprovalService.approveTool("s1", "github", "test");

        verify(agentStore).save(any(AgentCheckpointEntity.class));
    }

    @Test
    void test_approveTool_throwsWhenNoCheckpoint() {
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

        Assertions.assertThrows(CheckpointResolutionException.class, () -> {
            checkpointApprovalService.approveTool("s1", "github", "test");
        });

        verify(agentStore, never()).save(any());
    }

    @Test
    void test_approveAll_approvesAllPendingTools() throws Exception {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setPendingToolName("parallel|github,sandboxexec");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\",\"sandboxexec\":\"PENDING\"}");
        
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        checkpointApprovalService.approveAll("s1", "bulk approve");

        verify(agentStore).save(any(AgentCheckpointEntity.class));
    }

    @Test
    void test_triggerResume_callsResumeHandler() {
        AgentCheckpoint cp = new AgentCheckpointEntity();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        when(agentStore.findLatestNonArchivedCheckpoint("s1")).thenReturn(Optional.of(cp));
        when(agentStore.claimForResume(anyString(), anyString())).thenReturn(true);
        
        checkpointApprovalService.triggerResume(cp);
        verify(resumeHandler).resumeAfterBatchDecision(eq("s1"), eq("coordinator"), any(), any(), any());
    }
}
