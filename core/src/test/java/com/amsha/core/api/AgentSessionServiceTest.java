package com.amsha.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.TurnDispatchResult;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.MdcKeys;
import com.amsha.persistence.api.CheckpointStatus;

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
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceTest {

    private static final String TEST_USER = "test-user";

    @Mock
    AgentLoop agentLoop;

    @Mock
    TurnDispatchService turnDispatchService;

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
                turnDispatchService,
                reasoningEngine,
                agentStore,
                workspaceManager,
                heartbeatTracker,
                responseMapper,
                checkpointResumeHandler,
                checkpointApprovalService,
                auditService,
                vThreads,
                5);

        mockedScope = mockStatic(WorkspaceScopeHolder.class);
        mockedContext = mockStatic(WorkspaceContext.class);

        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        anyString(),
                        any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(6)).call());

        mockedContext.when(WorkspaceContext::resolveUserId).thenAnswer(inv -> MDC.get(MdcKeys.USER_ID));

        mockedContext.when(WorkspaceContext::getUserIdOrUnknown).thenAnswer(inv -> {
            String uid = (String) MDC.get(MdcKeys.USER_ID);
            return (uid != null && !uid.isBlank()) ? uid : "unknown";
        });

        mockedScope
                .when(() -> WorkspaceScopeHolder.run(anyString(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return null;
                });

        mockedScope
                .when(() -> WorkspaceScopeHolder.call(anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());

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
        String sessionId = service.createSession(TEST_USER, AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker).recordHeartbeat(sessionId, AutonomyLevel.FULL);
    }

    @Test
    void testCreateSession_withUserId_bindsOwner() {
        String sessionId = service.createSession(TEST_USER, AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker).tryBindOwner(sessionId, TEST_USER);
    }

    @Test
    void testCreateSession_withoutMdcUserId_doesNotBindOwner() {
        MDC.remove(MdcKeys.USER_ID);
        String sessionId = service.createSession("unknown", AutonomyLevel.SUPERVISED);
        assertNotNull(sessionId);
        verify(heartbeatTracker, never()).tryBindOwner(anyString(), anyString());
    }

    @Test
    void testCreateSession_storesExpectedTestLabels() {
        String sessionId = service.createSession(
                TEST_USER, AutonomyLevel.FULL, Map.of("tool_risk", "LOW_RISK", "answer_quality", "ACCEPTABLE"));
        assertEquals("LOW_RISK", service.getExpectedTestLabel(sessionId, "tool_risk"));
        assertEquals("ACCEPTABLE", service.getExpectedTestLabel(sessionId, "answer_quality"));
    }

    @Test
    void testCreateSession_rejectsFullAutonomyWithRequireApprovalLabel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createSession(TEST_USER, AutonomyLevel.FULL, Map.of("tool_risk", "REQUIRE_APPROVAL")));
    }

    @Test
    void testRunSync_finished_returns200() {
        RunAgentRequest request = new RunAgentRequest("goal", "coordinator", 5);
        AgentResult result = mock(AgentResult.Finished.class);
        when(turnDispatchService.dispatch(any()))
                .thenReturn(new TurnDispatchResult(
                        Optional.of("sess1"), Optional.of("coordinator"), Optional.of(result), Optional.empty()));
        AgentResponseMapper.MappedResult mapped =
                new AgentResponseMapper.MappedResult(new AgentResponseMapper.AgentResponse("done", 10, 5), 200);
        when(responseMapper.mapResult(any(), any(Boolean.class))).thenReturn(mapped);

        ServiceResult<Object> sr = service.runSync("sess1", TEST_USER, request);
        assertTrue(sr.isSuccess());
        assertNotNull(sr.value());
        assertEquals(200, ((AgentResponseMapper.MappedResult) sr.value()).suggestedHttpStatus());
    }

    @Test
    void testRunSync_complianceViolation_returns451() {
        RunAgentRequest request = new RunAgentRequest("goal", "coordinator", 5);
        AgentResult result = mock(AgentResult.Failed.class);
        when(turnDispatchService.dispatch(any()))
                .thenReturn(new TurnDispatchResult(
                        Optional.of("sess1"), Optional.of("coordinator"), Optional.of(result), Optional.empty()));
        AgentResponseMapper.MappedResult mapped =
                new AgentResponseMapper.MappedResult(new AmshaErrorResponse("Violation"), 451);
        when(responseMapper.mapResult(any(), any(Boolean.class))).thenReturn(mapped);

        ServiceResult<Object> sr = service.runSync("sess1", TEST_USER, request);
        assertTrue(sr.isSuccess());
        assertEquals(451, ((AgentResponseMapper.MappedResult) sr.value()).suggestedHttpStatus());
    }

    @Test
    void testApprove_noCheckpoint_returns409() {
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        ServiceResult<Object> sr = service.approve("sess1", TEST_USER, "coordinator");
        Assertions.assertFalse(sr.isSuccess());
        assertEquals("NO_PENDING_APPROVAL", sr.errorCode());
    }

    @Test
    void testApprove_notAwaiting_returns409() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setStatus(CheckpointStatus.COMPLETED);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));

        ServiceResult<Object> sr = service.approve("sess1", TEST_USER, "coordinator");
        Assertions.assertFalse(sr.isSuccess());
        assertEquals("NO_PENDING_APPROVAL", sr.errorCode());
    }

    @Test
    void testDeny_success_routesThroughApprovalService() {
        AgentCheckpoint cp = new AgentCheckpointRecord();
        cp.setId(123L);
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.of(cp));
        when(checkpointApprovalService.denyAll(anyString(), anyString())).thenReturn(cp);

        ServiceResult<Object> sr = service.deny("sess1", TEST_USER, "coordinator");
        assertTrue(sr.isSuccess());
        verify(checkpointApprovalService).denyAll("sess1", "Human denied via REST");
        verify(checkpointApprovalService).triggerResume(cp);
    }

    @Test
    void testDeny_noCheckpoint_returns409() {
        when(agentStore.load(anyString(), anyString())).thenReturn(Optional.empty());
        ServiceResult<Object> sr = service.deny("sess1", TEST_USER, "coordinator");
        Assertions.assertFalse(sr.isSuccess());
        assertEquals("NO_PENDING_APPROVAL", sr.errorCode());
    }

    @Test
    void testGetStatus_notFound_returns404() {
        when(heartbeatTracker.exists(anyString())).thenReturn(false);
        ServiceResult<Object> sr = service.getStatus("sess1", TEST_USER, "coordinator");
        Assertions.assertFalse(sr.isSuccess());
        assertEquals("SESSION_NOT_FOUND", sr.errorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRunAsync_denyGoal_routesThroughApprovalService() throws Exception {
        RunAgentRequest request = new RunAgentRequest("deny", "coordinator", 5);
        AgentCheckpoint cp = new AgentCheckpointRecord();
        when(checkpointApprovalService.denyAll(anyString(), anyString())).thenReturn(cp);

        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(0)).run();
                    return null;
                })
                .when(vThreads)
                .execute(any(Runnable.class));

        Consumer<ServiceResult<Object>> callback = mock(Consumer.class);
        service.runAsync("sess1", TEST_USER, request, callback);

        verify(checkpointApprovalService).denyAll("sess1", "Human denied via REST");
        verify(checkpointApprovalService).triggerResume(cp);
        verify(callback).accept(any(ServiceResult.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testRunAsync_approveGoal_routesThroughApprovalService() throws Exception {
        RunAgentRequest request = new RunAgentRequest("approve", "coordinator", 5);
        AgentCheckpoint cp = new AgentCheckpointRecord();
        when(checkpointApprovalService.approveAll(anyString(), anyString())).thenReturn(cp);

        doAnswer(inv -> {
                    ((Runnable) inv.getArgument(0)).run();
                    return null;
                })
                .when(vThreads)
                .execute(any(Runnable.class));

        Consumer<ServiceResult<Object>> callback = mock(Consumer.class);
        service.runAsync("sess1", TEST_USER, request, callback);

        verify(checkpointApprovalService).approveAll("sess1", "Human approved via REST");
        verify(checkpointApprovalService).triggerResume(cp);
        verify(callback).accept(any(ServiceResult.class));
    }

    @Test
    void testTerminateSession_callsWorkspaceManager() {
        service.terminateSession("sess1", TEST_USER);
        verify(workspaceManager).terminateSession("sess1");
    }

    @Test
    void validateSession_crossKeyAccess_firesUnauthorizedAuditAndReturns401() {
        when(heartbeatTracker.exists("sess-1")).thenReturn(true);
        when(heartbeatTracker.getOwner("sess-1")).thenReturn(Optional.of("key-A"));

        Optional<ServiceResult<Object>> result = service.validateSession("sess-1", "key-B");

        Assertions.assertTrue(result.isPresent());
        assertEquals("SESSION_ACCESS_DENIED", result.get().errorCode());
        verify(auditService)
                .recordMandatory(
                        ArgumentMatchers.eq("sess-1"),
                        ArgumentMatchers.eq("key-B"),
                        any(),
                        ArgumentMatchers.eq(SecurityEventType.UNAUTHORIZED_ACCESS),
                        any());
    }
}
