package com.amsha.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.trace.SafeSymbolMetadata;
import com.amsha.core.agent.trace.TraceSymbolicationService;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CockpitResourceTest {

    private CockpitResource resource;
    private TraceSymbolicationService symbolicationService;
    private ExecutionTraceStorePort traceStore;
    private SessionCatalogStore sessionCatalogStore;
    private AuditService auditService;
    private SecurityContext securityContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new CockpitResource();
        symbolicationService = mock(TraceSymbolicationService.class);
        traceStore = mock(ExecutionTraceStorePort.class);
        sessionCatalogStore = mock(SessionCatalogStore.class);
        auditService = mock(AuditService.class);
        securityContext = mock(SecurityContext.class);

        Instance<ExecutionTraceStorePort> storeInstance = mock(Instance.class);
        when(storeInstance.isUnsatisfied()).thenReturn(false);
        when(storeInstance.get()).thenReturn(traceStore);

        resource.symbolicationService = symbolicationService;
        resource.traceStoreInstance = storeInstance;
        resource.sessionCatalogStore = sessionCatalogStore;
        resource.auditService = auditService;
        resource.securityContext = securityContext;

        Principal principal = () -> "user-alice";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
    }

    private static SessionCatalogEntry createSession(String sessionId, String ownerKey) {
        return new SessionCatalogEntry(
                "cat-1",
                ownerKey,
                "web",
                "ch-1",
                sessionId,
                "coordinator",
                "goal",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");
    }

    @Test
    void symbolicateMissingParamReturnsBadRequest() {
        Response response = resource.symbolicate("");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    void symbolicateFoundReturnsOk() {
        SafeSymbolMetadata meta = new SafeSymbolMetadata(
                "ToolInvocationGuard",
                "station_05_tool_guard",
                "Tool Guard Check",
                "hash123",
                "Governance",
                "Invariant");
        when(symbolicationService.symbolicate("ToolInvocationGuard", false)).thenReturn(Optional.of(meta));

        Response response = resource.symbolicate("ToolInvocationGuard");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(meta, response.getEntity());
    }

    @Test
    void listRunsSessionNotFoundReturns404() {
        when(sessionCatalogStore.bySessionId("missing-session")).thenReturn(Optional.empty());

        Response response = resource.listRuns("missing-session", 0, 50);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void listRunsUnownedSessionNonInternalReturns404() {
        when(sessionCatalogStore.bySessionId("sess-bob"))
                .thenReturn(Optional.of(createSession("sess-bob", "user-bob")));
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response response = resource.listRuns("sess-bob", 0, 50);
        // Must return 404 to avoid disclosing existence
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(auditService, never()).recordMandatory(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void listRunsUnownedSessionInternalReturnsRunsAndAudits() {
        when(sessionCatalogStore.bySessionId("sess-bob"))
                .thenReturn(Optional.of(createSession("sess-bob", "user-bob")));
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        ExecutionRunSummaryDto summary = new ExecutionRunSummaryDto(
                "sess-bob:turn-1", "sess-bob", 1, "REACT", 10L, 9L, "OK", Instant.now(), Instant.now());
        when(traceStore.findRunsBySessionId(eq("sess-bob"), anyInt(), anyInt())).thenReturn(List.of(summary));

        Response response = resource.listRuns("sess-bob", 0, 50);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        verify(auditService)
                .recordMandatory(
                        eq("sess-bob"),
                        eq("user-alice"),
                        eq("operator"),
                        eq(SecurityEventType.SESSION_INSPECTED),
                        anyString());
    }

    @Test
    void listRunsOwnedSessionReturnsRunsWithoutAudit() {
        when(sessionCatalogStore.bySessionId("sess-alice"))
                .thenReturn(Optional.of(createSession("sess-alice", "user-alice")));

        ExecutionRunSummaryDto summary = new ExecutionRunSummaryDto(
                "sess-alice:turn-1", "sess-alice", 1, "REACT", 5L, 4L, "OK", Instant.now(), Instant.now());
        when(traceStore.findRunsBySessionId(eq("sess-alice"), anyInt(), anyInt()))
                .thenReturn(List.of(summary));

        Response response = resource.listRuns("sess-alice", 0, 50);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(auditService, never()).recordMandatory(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void getRunTraceOwnedSessionReturnsBoundedTraces() {
        String runId = "sess-alice:turn-1";
        when(sessionCatalogStore.bySessionId("sess-alice"))
                .thenReturn(Optional.of(createSession("sess-alice", "user-alice")));

        ExecutionTraceDto dto = new ExecutionTraceDto(
                "evt-1",
                runId,
                "sess-alice",
                1,
                0L,
                "tool_start",
                "station_07_tool_execution",
                "OK",
                "LOW",
                "detail",
                100,
                50,
                10L,
                1,
                1,
                Instant.now(),
                "REACT",
                1,
                Instant.now());
        when(traceStore.findByRunIdBounded(eq(runId), anyLong(), eq(10L), anyInt(), anyInt()))
                .thenReturn(List.of(dto));

        Response response = resource.getRunTrace(runId, 0L, 10L, 0, 1000);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // Limit should be clamped to MAX_LIMIT (500)
        verify(traceStore).findByRunIdBounded(runId, 0L, 10L, 0, 500);
    }

    @Test
    void getRunTraceDoesNotClampToSequenceToMaxLimit() {
        String runId = "sess-alice:turn-1";
        when(sessionCatalogStore.bySessionId("sess-alice"))
                .thenReturn(Optional.of(createSession("sess-alice", "user-alice")));
        when(traceStore.findByRunIdBounded(eq(runId), anyLong(), eq(1500L), anyInt(), anyInt()))
                .thenReturn(List.of());

        Response response = resource.getRunTrace(runId, 100L, 1500L, 0, 100);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        // toSequence should remain 1500L, not clamped to 500
        verify(traceStore).findByRunIdBounded(runId, 100L, 1500L, 0, 100);
    }

    @Test
    void getRunTraceNonOwnerNonInternalReturns404() {
        // runId resolves to a session owned by another user; caller is non-internal.
        String runId = "sess-bob:turn-1";
        when(sessionCatalogStore.bySessionId("sess-bob"))
                .thenReturn(Optional.of(createSession("sess-bob", "user-bob")));
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response response = resource.getRunTrace(runId, 0L, null, 0, 50);
        // Must return 404 to avoid disclosing existence of an unowned session
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        verify(auditService, never()).recordMandatory(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void getRunTraceForgedRunIdReturns404() {
        // Forged identifier without the ":turn-" convention cannot resolve to a real session.
        String forgedRunId = "forged-runid-no-turn";
        when(sessionCatalogStore.bySessionId(forgedRunId)).thenReturn(Optional.empty());
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response response = resource.getRunTrace(forgedRunId, 0L, null, 0, 50);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void listRunsClampsLimitTo100() {
        // Regardless of the requested limit, the effective limit passed to the store must be bounded.
        when(sessionCatalogStore.bySessionId("sess-alice"))
                .thenReturn(Optional.of(createSession("sess-alice", "user-alice")));
        when(traceStore.findRunsBySessionId(eq("sess-alice"), anyInt(), anyInt()))
                .thenReturn(List.of());

        // Request a huge limit; assert the effective value is clamped to LIST_RUNS_MAX (100).
        Response response = resource.listRuns("sess-alice", 0, 10_000);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(traceStore).findRunsBySessionId(eq("sess-alice"), anyInt(), limitCaptor.capture());
        assertTrue(limitCaptor.getValue() <= 100, "effective limit should be clamped to 100");
    }

    @Test
    void symbolicateUnknownSymbolReturnsNotFound() {
        // Symbol absent from the allowlist must resolve to NOT_FOUND, not leak metadata.
        when(symbolicationService.symbolicate("UnknownSymbol", false)).thenReturn(Optional.empty());

        Response response = resource.symbolicate("UnknownSymbol");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }
}
