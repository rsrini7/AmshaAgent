package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amsha.core.api.AgentResponseMapper;
import com.amsha.core.api.AgentSessionService;
import com.amsha.core.api.dto.ActiveAgentResponse;
import com.amsha.core.api.dto.CreateSessionRequest;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Deep unit tests for {@link AgentResource}.
 * Tests the real method logic (resolveUserId, toResponse, createSession, etc.).
 * Only the injected service layer is mocked.
 */
class AgentResourceDeepTest {

    @Mock
    AgentSessionService sessionService;

    @Mock
    AgentResponseMapper responseMapper;

    @Mock
    CheckpointCrudPort checkpointCrud;

    @Mock
    SecurityContext securityContext;

    AgentResource resource;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        resource = new AgentResource();
        resource.sessionService = sessionService;
        resource.responseMapper = responseMapper;
        resource.checkpointCrud = checkpointCrud;
        resource.securityContext = securityContext;
    }

    // ── createSession ────────────────────────────────────────────────────

    @Nested
    class CreateSessionTests {

        @Test
        void createSession_withRequest_returns201() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);
            when(sessionService.createSession("user1", null)).thenReturn("session-123");

            CreateSessionRequest request = new CreateSessionRequest(null);
            Response response = resource.createSession(request);

            assertEquals(201, response.getStatus());
            assertNotNull(response.getEntity());
        }

        @Test
        void createSession_nullRequest_usesNullAutonomy() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);
            when(sessionService.createSession("user1", null)).thenReturn("session-123");

            Response response = resource.createSession(null);

            assertEquals(201, response.getStatus());
        }

        @Test
        void createSession_noSecurityContext_usesWorkspaceFallback() {
            resource.securityContext = null;
            when(sessionService.createSession(anyString(), any())).thenReturn("session-123");

            CreateSessionRequest request = new CreateSessionRequest(null);
            Response response = resource.createSession(request);

            assertEquals(201, response.getStatus());
        }

        @Test
        void createSession_nullPrincipal_usesWorkspaceFallback() {
            when(securityContext.getUserPrincipal()).thenReturn(null);
            when(sessionService.createSession(anyString(), any())).thenReturn("session-123");

            CreateSessionRequest request = new CreateSessionRequest(null);
            Response response = resource.createSession(request);

            assertEquals(201, response.getStatus());
        }
    }

    // ── listSessions ─────────────────────────────────────────────────────

    @Nested
    class ListSessionsTests {

        @Test
        void listSessions_returnsMapWithSessionsAndCount() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);
            Map<String, Instant> sessions = new HashMap<>();
            sessions.put("s1", Instant.now());
            sessions.put("s2", Instant.now());
            when(sessionService.listSessions("user1")).thenReturn(sessions);

            Response response = resource.listSessions();

            assertEquals(200, response.getStatus());
            Map<?, ?> entity = (Map<?, ?>) response.getEntity();
            assertEquals(2, entity.get("count"));
        }
    }

    // ── listActiveAgents ─────────────────────────────────────────────────

    @Nested
    class ListActiveAgentsTests {

        @Test
        void listActiveAgents_withCheckpoints_returnsAgentSummary() {
            CheckpointDto cp = new CheckpointDto(
                    1L,
                    "s1",
                    "coordinator",
                    "IN_PROGRESS",
                    "FULL",
                    null,
                    null,
                    "cli",
                    "do something",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    Instant.now(),
                    Instant.now());
            when(checkpointCrud.findAllActive()).thenReturn(List.of(cp));

            Response response = resource.listActiveAgents();

            assertEquals(200, response.getStatus());
            var entity = (ActiveAgentResponse) response.getEntity();
            assertEquals(1, entity.count());
        }

        @Test
        void listActiveAgents_noCheckpoints_returnsEmptyList() {
            when(checkpointCrud.findAllActive()).thenReturn(List.of());

            Response response = resource.listActiveAgents();

            assertEquals(200, response.getStatus());
            var entity = (ActiveAgentResponse) response.getEntity();
            assertEquals(0, entity.count());
        }
    }

    // ── runAgentSync ─────────────────────────────────────────────────────

    @Nested
    class RunAgentSyncTests {

        @Test
        void runAgentSync_success_returns200() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.success("done");
            when(sessionService.runSync(eq("s1"), eq("user1"), any())).thenReturn(result);

            RunAgentRequest request = new RunAgentRequest("do something", null, null);
            Response response = resource.runAgentSync("s1", request);

            assertEquals(200, response.getStatus());
        }

        @Test
        void runAgentSync_sessionNotFound_returns404() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("SESSION_NOT_FOUND", "Not found");
            when(sessionService.runSync(eq("s1"), eq("user1"), any())).thenReturn(result);

            RunAgentRequest request = new RunAgentRequest("goal", null, null);
            Response response = resource.runAgentSync("s1", request);

            assertEquals(404, response.getStatus());
        }
    }

    // ── terminateSession ─────────────────────────────────────────────────

    @Nested
    class TerminateSessionTests {

        @Test
        void terminateSession_success_returns204() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.success(null);
            when(sessionService.terminateSession("s1", "user1")).thenReturn(result);

            Response response = resource.terminateSession("s1");

            assertEquals(204, response.getStatus());
        }

        @Test
        void terminateSession_failure_returnsErrorStatus() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("SESSION_NOT_FOUND", "Not found");
            when(sessionService.terminateSession("s1", "user1")).thenReturn(result);

            Response response = resource.terminateSession("s1");

            assertEquals(404, response.getStatus());
        }

        @Test
        void terminateSession_sessionBusy_returns409Conflict() {
            // F12 resume guard surfaces as a retryable conflict, not a 500
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error(
                    "SESSION_BUSY", "Cannot terminate session while it is actively resuming its batch");
            when(sessionService.terminateSession("s1", "user1")).thenReturn(result);

            Response response = resource.terminateSession("s1");

            assertEquals(409, response.getStatus());
        }
    }

    // ── approve / deny ───────────────────────────────────────────────────

    @Nested
    class ApproveDenyTests {

        @Test
        void approve_success_returns200() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.success("approved");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(200, response.getStatus());
        }

        @Test
        void deny_success_returns200() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.success("denied");
            when(sessionService.deny("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.denySession("s1", "coordinator");

            assertEquals(200, response.getStatus());
        }

        @Test
        void approve_accessDenied_returns401() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("SESSION_ACCESS_DENIED", "Access denied");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(401, response.getStatus());
        }

        @Test
        void deny_noPendingApproval_returns409() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("NO_PENDING_APPROVAL", "Nothing pending");
            when(sessionService.deny("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.denySession("s1", "coordinator");

            assertEquals(409, response.getStatus());
        }
    }

    // ── toResponse switch coverage ───────────────────────────────────────

    @Nested
    class ToResponseTests {

        @Test
        void toResponse_unknownRole_returns400() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("UNKNOWN_ROLE", "Unknown role");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(400, response.getStatus());
        }

        @Test
        void toResponse_forkBomb_returns400() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("FORK_BOMB_REJECTED", "Too deep");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(400, response.getStatus());
        }

        @Test
        void toResponse_awaitingApproval_returns409() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("AWAITING_APPROVAL", "Waiting");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(409, response.getStatus());
        }

        @Test
        void toResponse_budget_returns429() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("BUDGET", "Budget exceeded");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(429, response.getStatus());
        }

        @Test
        void toResponse_system_returns500() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("SYSTEM", "Internal error");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(500, response.getStatus());
        }

        @Test
        void toResponse_unknownError_returns500() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            ServiceResult<Object> result = ServiceResult.error("SOME_OTHER", "Something");
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(500, response.getStatus());
        }

        @Test
        void toResponse_successWithMappedResult_usesSuggestedStatus() {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("user1");
            when(securityContext.getUserPrincipal()).thenReturn(principal);

            AgentResponseMapper.MappedResult mr = new AgentResponseMapper.MappedResult("entity", 202);
            ServiceResult<Object> result = ServiceResult.success(mr);
            when(sessionService.approve("s1", "user1", "coordinator")).thenReturn(result);

            Response response = resource.approveSession("s1", "coordinator");

            assertEquals(202, response.getStatus());
        }
    }
}
