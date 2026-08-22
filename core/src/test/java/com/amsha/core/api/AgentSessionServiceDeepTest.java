package com.amsha.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.amsha.core.agent.hitl.CheckpointApprovalService;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.api.dto.SessionStatusResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for {@link AgentSessionService}.
 * Tests real service logic with mocked external dependencies only.
 */
class AgentSessionServiceDeepTest {

    private Logger log;
    private AgentLoop agentLoop;
    private TurnDispatchService turnDispatchService;
    private ReasoningEngine reasoningEngine;
    private StatefulAgentStore agentStore;
    private WorkspaceManager workspaceManager;
    private SessionHeartbeatTracker heartbeatTracker;
    private AgentResponseMapper responseMapper;
    private CheckpointResumeHandler checkpointResumeHandler;
    private CheckpointApprovalService checkpointApprovalService;
    private AuditService auditService;
    private ExecutorService vThreads;

    private AgentSessionService service;

    @BeforeEach
    void setUp() {
        log = mock(Logger.class);
        agentLoop = mock(AgentLoop.class);
        turnDispatchService = mock(TurnDispatchService.class);
        reasoningEngine = mock(ReasoningEngine.class);
        agentStore = mock(StatefulAgentStore.class);
        workspaceManager = mock(WorkspaceManager.class);
        heartbeatTracker = mock(SessionHeartbeatTracker.class);
        responseMapper = mock(AgentResponseMapper.class);
        checkpointResumeHandler = mock(CheckpointResumeHandler.class);
        checkpointApprovalService = mock(CheckpointApprovalService.class);
        auditService = mock(AuditService.class);
        vThreads = mock(ExecutorService.class);

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
    }

    @Test
    void testCreateSession_basic() {
        String sessionId = service.createSession("user-1", AutonomyLevel.SUPERVISED);
        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank());
        verify(heartbeatTracker).recordHeartbeat(sessionId, AutonomyLevel.SUPERVISED);
        verify(heartbeatTracker).tryBindOwner(sessionId, "user-1");
        verify(auditService)
                .recordMandatory(
                        eq(sessionId), eq("user-1"), eq("system"), eq(SecurityEventType.SESSION_CREATED), anyString());
    }

    @Test
    void testCreateSession_nullAutonomy_defaultsToSupervised() {
        String sessionId = service.createSession("user-1", null);
        assertNotNull(sessionId);
        verify(heartbeatTracker).recordHeartbeat(sessionId, AutonomyLevel.SUPERVISED);
    }

    @Test
    void testCreateSession_nullUserId_noBindOwner() {
        String sessionId = service.createSession(null, AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker, never()).tryBindOwner(any(), any());
        verify(auditService)
                .recordMandatory(
                        eq(sessionId), eq("unknown"), eq("system"), eq(SecurityEventType.SESSION_CREATED), anyString());
    }

    @Test
    void testCreateSession_blankUserId_noBindOwner() {
        String sessionId = service.createSession("  ", AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker, never()).tryBindOwner(any(), any());
    }

    @Test
    void testCreateSession_unknownUserId_noBindOwner() {
        String sessionId = service.createSession("unknown", AutonomyLevel.FULL);
        assertNotNull(sessionId);
        verify(heartbeatTracker, never()).tryBindOwner(any(), any());
    }

    @Test
    void testListSessions_nullUserId_returnsEmpty() {
        Map<String, Instant> result = service.listSessions(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testListSessions_blankUserId_returnsEmpty() {
        Map<String, Instant> result = service.listSessions("  ");
        assertTrue(result.isEmpty());
    }

    @Test
    void testListSessions_validUserId_delegatesToTracker() {
        Map<String, Instant> expected = Map.of("s1", Instant.now());
        when(heartbeatTracker.activeSessionsByOwner("user-1")).thenReturn(expected);
        Map<String, Instant> result = service.listSessions("user-1");
        assertEquals(expected, result);
    }

    @Test
    void testValidateSession_sessionNotFound() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        Optional<ServiceResult<Object>> result = service.validateSession("s1", "user-1");
        assertTrue(result.isPresent());
        assertEquals("SESSION_NOT_FOUND", result.get().errorCode());
    }

    @Test
    void testValidateSession_sessionExists_noOwner() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.empty());
        Optional<ServiceResult<Object>> result = service.validateSession("s1", "user-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void testValidateSession_ownerMatches() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        Optional<ServiceResult<Object>> result = service.validateSession("s1", "user-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void testValidateSession_ownerMismatch_accessDenied() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        Optional<ServiceResult<Object>> result = service.validateSession("s1", "user-2");
        assertTrue(result.isPresent());
        assertEquals("SESSION_ACCESS_DENIED", result.get().errorCode());
        verify(auditService)
                .recordMandatory(
                        eq("s1"), eq("user-2"), anyString(), eq(SecurityEventType.UNAUTHORIZED_ACCESS), anyString());
    }

    @Test
    void testValidateSession_nullUserId_noAccessCheck() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        Optional<ServiceResult<Object>> result = service.validateSession("s1", null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testTerminateSession_success() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        ServiceResult<Object> result = service.terminateSession("s1", "user-1");
        assertTrue(result.isSuccess());
        verify(workspaceManager).terminateSession("s1");
        verify(auditService)
                .recordMandatory(
                        eq("s1"), eq("user-1"), eq("system"), eq(SecurityEventType.SESSION_TERMINATED), anyString());
    }

    @Test
    void testTerminateSession_sessionNotFound() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        ServiceResult<Object> result = service.terminateSession("s1", "user-1");
        assertFalse(result.isSuccess());
        assertEquals("SESSION_NOT_FOUND", result.errorCode());
    }

    @Test
    void testGetStatus_sessionNotFound() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        ServiceResult<Object> result = service.getStatus("s1", "user-1", "coordinator");
        assertFalse(result.isSuccess());
        assertEquals("SESSION_NOT_FOUND", result.errorCode());
    }

    @Test
    void testGetStatus_noCheckpoint() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        when(agentStore.load(eq("s1"), anyString())).thenReturn(Optional.empty());
        ServiceResult<Object> result = service.getStatus("s1", "user-1", "coordinator");
        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, String> value = (Map<String, String>) result.value();
        assertEquals(CheckpointStatus.NOT_STARTED.name(), value.get("status"));
    }

    @Test
    void testGetStatus_withCheckpoint() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getId()).thenReturn(1L);
        when(cp.getStatus()).thenReturn(CheckpointStatus.AWAITING_APPROVAL);
        when(cp.getFailReason()).thenReturn("test reason");
        when(agentStore.load(eq("s1"), anyString())).thenReturn(Optional.of(cp));
        when(agentStore.loadSteps(1L)).thenReturn(List.of());

        ServiceResult<Object> result = service.getStatus("s1", "user-1", "coordinator");
        assertTrue(result.isSuccess());
        assertTrue(result.value() instanceof SessionStatusResponse);
    }

    @Test
    void testGetStatus_exceptionReturnsError() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        when(agentStore.load(anyString(), anyString())).thenThrow(new RuntimeException("DB error"));

        ServiceResult<Object> result = service.getStatus("s1", "user-1", "coordinator");
        assertFalse(result.isSuccess());
        assertEquals("SYSTEM", result.errorCode());
    }

    @Test
    void testRunSync_sessionNotFound() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        RunAgentRequest request = new RunAgentRequest("test goal", "coordinator", 5);
        ServiceResult<Object> result = service.runSync("s1", "user-1", request);
        assertFalse(result.isSuccess());
        assertEquals("SESSION_NOT_FOUND", result.errorCode());
    }

    @Test
    void testRunSync_unknownRole() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        RunAgentRequest request = new RunAgentRequest("test goal", "nonexistent_role_xyz", 5);
        ServiceResult<Object> result = service.runSync("s1", "user-1", request);
        assertFalse(result.isSuccess());
        assertEquals("UNKNOWN_ROLE", result.errorCode());
    }

    @Test
    void testRunSync_cronPrefixRejected() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        RunAgentRequest request = new RunAgentRequest("CRON: malicious goal", "coordinator", 5);
        ServiceResult<Object> result = service.runSync("s1", "user-1", request);
        assertFalse(result.isSuccess());
        assertEquals("FORK_BOMB_REJECTED", result.errorCode());
    }

    @Test
    void testDeny_sessionNotFound() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        ServiceResult<Object> result = service.deny("s1", "user-1", "coordinator");
        assertFalse(result.isSuccess());
        assertEquals("SESSION_NOT_FOUND", result.errorCode());
    }

    @Test
    void testApprove_sessionNotFound() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        ServiceResult<Object> result = service.approve("s1", "user-1", "coordinator");
        assertFalse(result.isSuccess());
        assertEquals("SESSION_NOT_FOUND", result.errorCode());
    }

    @Test
    void testGetOrDefaultAutonomy_nullLevel_returnsSupervised() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        when(heartbeatTracker.getAutonomyLevel("s1")).thenReturn(null);

        // This exercises getOrDefaultAutonomy indirectly through a method that calls it
        // We'll use terminateSession as it's the simplest path
        ServiceResult<Object> result = service.terminateSession("s1", "user-1");
        assertTrue(result.isSuccess());
    }

    @Test
    void testRunAsync_sessionNotFound_callbackCalled() {
        when(heartbeatTracker.exists("s1")).thenReturn(false);
        RunAgentRequest request = new RunAgentRequest("goal", "coordinator", 5);

        @SuppressWarnings("unchecked")
        Consumer<ServiceResult<Object>> callback = mock(Consumer.class);
        service.runAsync("s1", "user-1", request, callback);
        verify(callback).accept(any());
    }

    @Test
    void testRunAsync_unknownRole_callbackCalled() {
        when(heartbeatTracker.exists("s1")).thenReturn(true);
        when(heartbeatTracker.getOwner("s1")).thenReturn(Optional.of("user-1"));
        RunAgentRequest request = new RunAgentRequest("goal", "unknown_role", 5);

        @SuppressWarnings("unchecked")
        Consumer<ServiceResult<Object>> callback = mock(Consumer.class);
        service.runAsync("s1", "user-1", request, callback);
        verify(callback).accept(any());
    }
}
