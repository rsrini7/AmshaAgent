package com.arcclaw.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;

import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceTest {

    @Mock
    AgentLoop agentLoop;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    WorkspaceManager workspaceManager;

    @Mock
    SessionHeartbeatTracker heartbeatTracker;

    @Mock
    DevSecretSeeder devSeeder;

    @Mock
    AgentResponseMapper responseMapper;

    @Mock
    ReflexRegistry reflexRegistry;

    @Mock
    ExecutorService vThreads;

    @Mock
    Logger log;

    AgentSessionService service;

    private MockedStatic<WorkspaceScopeHolder> mockedScope;
    private MockedStatic<WorkspaceContext> mockedContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new AgentSessionService(log, agentLoop, reasoningEngine, agentStore, workspaceManager,
                heartbeatTracker, devSeeder, responseMapper, reflexRegistry, vThreads);
        
        mockedScope = mockStatic(WorkspaceScopeHolder.class);
        mockedContext = mockStatic(WorkspaceContext.class);

        // Default behavior: just execute the lambda
        mockedScope.when(() -> WorkspaceScopeHolder.call(anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        
        mockedContext.when(() -> WorkspaceContext.run(anyString(), anyString(), any(), any(AutonomyLevel.class), anyString(), any(ChannelType.class), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(6)).call());

        // Ensure session exists by default so we don't return 404 in validateSession.
        // Marked lenient to avoid UnnecessaryStubbingException in tests that don't call validateSession.
        lenient().when(heartbeatTracker.exists(anyString())).thenReturn(true);
        lenient().when(heartbeatTracker.getAutonomyLevel(anyString())).thenReturn(AutonomyLevel.SUPERVISED);
    }

    @AfterEach
    void tearDown() {
        mockedScope.close();
        mockedContext.close();
    }

    @Test
    void testCreateSession_recordsHeartbeatAndReturnsId() {
        String sessionId = service.createSession(AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker).recordHeartbeat(sessionId, AutonomyLevel.FULL);
    }

    @Test
    void testRunSync_finished_returns200() {
        RunAgentRequest request = new RunAgentRequest("goal", "coordinator", 5);
        AgentResult result = mock(AgentResult.Finished.class);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt(), any())).thenReturn(result);
        when(responseMapper.mapResult(any(), any(Boolean.class))).thenReturn(Response.ok().build());

        Response response = service.runSync("sess1", request);
        assertNotNull(response, "Response should not be null");
        assertEquals(200, response.getStatus());
    }

    @Test
    void testRunSync_complianceViolation_returns451() {
        RunAgentRequest request = new RunAgentRequest("goal", "coordinator", 5);
        AgentResult result = mock(AgentResult.Failed.class);
        when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt(), any())).thenReturn(result);
        when(responseMapper.mapResult(any(), any(Boolean.class))).thenReturn(Response.status(451).build());

        Response response = service.runSync("sess1", request);
        assertNotNull(response, "Response should not be null");
        assertEquals(451, response.getStatus());
    }

    @Test
    void testApprove_noCheckpoint_returns409() {
        // Mock session exists but checkpoint is missing
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        Response response = service.approve("sess1", "coordinator");
        assertEquals(409, response.getStatus());
    }

    @Test
    void testApprove_notAwaiting_returns409() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.COMPLETED);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));
        
        Response response = service.approve("sess1", "coordinator");
        assertEquals(409, response.getStatus());
    }

    @Test
    void testDeny_success_returns200WithDeniedStatus() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setId(123L);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));
        
        Response response = service.deny("sess1", "coordinator");
        assertEquals(200, response.getStatus());
        verify(agentStore).markFailed(123L, "Human denied via REST");
    }

    @Test
    void testDeny_noCheckpoint_returns409() {
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        Response response = service.deny("sess1", "coordinator");
        assertEquals(409, response.getStatus());
    }

    @Test
    void testGetStatus_notFound_returns404() {
        // Explicitly mock as non-existent for this specific test
        when(heartbeatTracker.exists(anyString())).thenReturn(false);
        Response response = service.getStatus("sess1", "coordinator");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testTerminateSession_callsWorkspaceManager() {
        Response response = service.terminateSession("sess1");
        assertEquals(204, response.getStatus());
        verify(workspaceManager).terminateSession("sess1");
    }
}
