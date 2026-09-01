package com.amsha.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.Optional;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.persistence.AsyncPersistenceEngine;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CheckpointApprovalServiceTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    CheckpointResumeHandler resumeHandler;

    @Mock
    Instance<CheckpointResumeHandler> resumeHandlerInstance;

    @Mock
    AsyncPersistenceEngine asyncEngine;

    @Mock
    AgentSseService agentSseService;

    CheckpointApprovalService checkpointApprovalService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        checkpointApprovalService =
                new CheckpointApprovalService(agentStore, agentSseService, new ObjectMapper(), resumeHandlerInstance);
        when(resumeHandlerInstance.isResolvable()).thenReturn(true);
        when(resumeHandlerInstance.get()).thenReturn(resumeHandler);
    }

    @Test
    void test_approveTool_marksToolApprovedAndResumesWhenAllDecided() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.updateBatchToolDecision(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        checkpointApprovalService.approveTool("s1", ToolCapability.GITHUB.toolName(), "test");

        verify(agentStore, never()).save(any(AgentCheckpointRecord.class));
        verify(agentStore).updateBatchToolDecision("s1", "coordinator", ToolCapability.GITHUB.toolName(), "APPROVED");
    }

    @Test
    void test_approveTool_doesNotResumeWhenToolsStillPending() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.updateBatchToolDecision(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        checkpointApprovalService.approveTool("s1", ToolCapability.GITHUB.toolName(), "test");

        verify(agentStore, never()).save(any(AgentCheckpointRecord.class));
        verify(agentStore).updateBatchToolDecision("s1", "coordinator", ToolCapability.GITHUB.toolName(), "APPROVED");
    }

    @Test
    void test_approveTool_throwsWhenNoCheckpoint() {
        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.empty());

        Assertions.assertThrows(CheckpointResolutionException.class, () -> {
            checkpointApprovalService.approveTool("s1", ToolCapability.GITHUB.toolName(), "test");
        });

        verify(agentStore, never()).save(any());
    }

    @Test
    void test_approveAll_approvesAllPendingTools() throws Exception {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setPendingToolName("parallel|" + ToolCapability.GITHUB.toolName() + ",sandboxexec");
        cp.setBatchToolDecisions(
                "{\"" + ToolCapability.GITHUB.toolName() + "\":\"PENDING\",\"sandboxexec\":\"PENDING\"}");

        when(agentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));
        when(agentStore.updateBatchToolDecision(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        checkpointApprovalService.approveAll("s1", "bulk approve");

        verify(agentStore, never()).save(any(AgentCheckpointRecord.class));
        verify(agentStore).updateBatchToolDecision("s1", "coordinator", ToolCapability.GITHUB.toolName(), "APPROVED");
        verify(agentStore).updateBatchToolDecision("s1", "coordinator", "sandboxexec", "APPROVED");
    }

    @Test
    void test_triggerResume_callsResumeHandler() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        when(agentStore.findLatestNonArchivedCheckpoint("s1")).thenReturn(Optional.of(cp));
        when(agentStore.claimForResume(anyString(), anyString())).thenReturn(true);

        checkpointApprovalService.triggerResume(cp);
        verify(resumeHandler).resumeAfterBatchDecision(eq("s1"), eq("coordinator"), any(), any(), any());
    }
}
