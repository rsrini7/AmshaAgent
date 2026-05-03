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

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.agent.hitl.CheckpointApprovalService;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.MdcKeys;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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
    AgentResponseMapper responseMapper;

    @Mock
    ReflexRegistry reflexRegistry;

    @Mock
    CheckpointResumeHandler checkpointResumeHandler;

    @Mock
    CheckpointApprovalService checkpointApprovalService;

    @Mock
    ExecutorService vThreads;

    @Mock
    Logger log;

    @Mock
    AuditService auditService;

    AgentSessionService service;

    private MockedStatic<WorkspaceScopeHolder> mockedScope;
    private MockedStatic<WorkspaceContext> mockedContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new AgentSessionService(
                log,
                agentLoop,
                reasoningEngine,
                agentStore,
                workspaceManager,
                heartbeatTracker,
                responseMapper,
                reflexRegistry,
                checkpointResumeHandler,
                checkpointApprovalService,
                auditService,
                vThreads,
                5
        );
        
        mockedScope = mockStatic(WorkspaceScopeHolder.class);
        mockedContext = mockStatic(WorkspaceContext.class);

        // Default behavior: just execute the lambda
        mockedContext.when(() -> WorkspaceContext.run(anyString(), anyString(), anyString(), any(AutonomyLevel.class), anyString(), anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(6)).call());
        
        mockedContext.when(WorkspaceContext::resolveUserId)
                .thenAnswer(inv -> MDC.get(MdcKeys.USER_ID));
        
        mockedScope.when(() -> WorkspaceScopeHolder.run(anyString(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return null;
                });
        
        mockedScope.when(() -> WorkspaceScopeHolder.call(anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());

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
        MDC.remove(MdcKeys.USER_ID);
        String sessionId = service.createSession(AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker).recordHeartbeat(sessionId, AutonomyLevel.FULL);
    }

    @Test
    void testCreateSession_withMdcUserId_bindsOwner() {
        MDC.put(MdcKeys.USER_ID, "key-owner-123");
        try {
            String sessionId = service.createSession(AutonomyLevel.FULL);
            assertNotNull(sessionId);
            verify(heartbeatTracker).tryBindOwner(sessionId, "key-owner-123");
        } finally {
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    @Test
    void testCreateSession_withoutMdcUserId_doesNotBindOwner() {
        MDC.remove(MdcKeys.USER_ID);
        String sessionId = service.createSession(AutonomyLevel.SUPERVISED);
        assertNotNull(sessionId);
        verify(heartbeatTracker, Mockito.never()).tryBindOwner(anyString(), anyString());
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
    void testDeny_success_routesThroughApprovalService() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setId(123L);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));
        when(checkpointApprovalService.denyAll(anyString(), anyString())).thenReturn(cp);
        
        Response response = service.deny("sess1", "coordinator");
        assertEquals(200, response.getStatus());
        verify(checkpointApprovalService).denyAll("sess1", "Human denied via REST");
        verify(checkpointApprovalService).triggerResume(cp);
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
    void testRunAsync_denyGoal_routesThroughApprovalService() throws Exception {
        RunAgentRequest request = new RunAgentRequest("deny", "coordinator", 5);
        AgentCheckpoint cp = new AgentCheckpoint();
        when(checkpointApprovalService.denyAll(anyString(), anyString())).thenReturn(cp);
        
        // Mocking VThreads execution to be synchronous for testing
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(vThreads).execute(any(Runnable.class));

        AsyncResponse asyncResponse = mock(AsyncResponse.class);
        service.runAsync("sess1", request, asyncResponse);
        
        verify(checkpointApprovalService).denyAll("sess1", "Human denied via REST");
        verify(checkpointApprovalService).triggerResume(cp);
        verify(asyncResponse).resume(any(Response.class));
    }

    @Test
    void testRunAsync_approveGoal_routesThroughApprovalService() throws Exception {
        RunAgentRequest request = new RunAgentRequest("approve", "coordinator", 5);
        AgentCheckpoint cp = new AgentCheckpoint();
        when(checkpointApprovalService.approveAll(anyString(), anyString())).thenReturn(cp);
        
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(vThreads).execute(any(Runnable.class));

        AsyncResponse asyncResponse = mock(AsyncResponse.class);
        service.runAsync("sess1", request, asyncResponse);
        
        verify(checkpointApprovalService).approveAll("sess1", "Human approved via REST");
        verify(checkpointApprovalService).triggerResume(cp);
        verify(asyncResponse).resume(any(Response.class));
    }

    @Test
    void testTerminateSession_callsWorkspaceManager() {
        Response response = service.terminateSession("sess1");
        assertEquals(204, response.getStatus());
        verify(workspaceManager).terminateSession("sess1");
    }

    @Test
    void validateSession_crossKeyAccess_firesUnauthorizedAuditAndReturns401() {
        when(heartbeatTracker.exists("sess-1")).thenReturn(true);
        when(heartbeatTracker.getOwner("sess-1")).thenReturn(Optional.of("key-A"));
        
        // WorkspaceContext.resolveUserId() returns "key-B"
        mockedContext.when(WorkspaceContext::resolveUserId).thenReturn("key-B");
        
        Optional<Response> result = service.validateSession("sess-1");
        
        Assertions.assertTrue(result.isPresent());
        assertEquals(401, result.get().getStatus());
        verify(auditService).recordMandatory(
            ArgumentMatchers.eq("sess-1"), ArgumentMatchers.eq("key-B"), any(),
            ArgumentMatchers.eq(SecurityEventType.UNAUTHORIZED_ACCESS), any());
    }
}
