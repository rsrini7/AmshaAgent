package com.amsha.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.amsha.core.agent.trace.TraceSymbolicationService;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@Path("cockpit")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class CockpitResource {

    static final int MAX_LIMIT = 500;

    // Runs listing uses a tighter cap than trace replay: listRuns returns lightweight
    // summaries for a session browser (bounded to 100), while getRunTrace returns heavy
    // per-event replay payloads (bounded to MAX_LIMIT = 500). Two distinct caps on purpose.
    private static final long LIST_RUNS_MAX = 100;

    private static final Logger log = Logger.getLogger(CockpitResource.class);

    @Inject
    TraceSymbolicationService symbolicationService;

    @Inject
    Instance<ExecutionTraceStorePort> traceStoreInstance;

    @Inject
    SessionCatalogStore sessionCatalogStore;

    @Inject
    AuditService auditService;

    // NOTE: @Context SecurityContext is injected per-request by RESTEasy Reactive.
    // On an @ApplicationScoped resource the principal must be populated for every incoming
    // request. It is only null outside a servlet/JAX-RS context (e.g. direct unit tests);
    // resolveUserId() and isInternal() therefore defensively tolerate a null context.
    @Context
    SecurityContext securityContext;

    /**
     * Resolves safe symbolication metadata for X-Ray drawer.
     *
     * <p>Enforces {@code isInternal()} authorization boundary at the resource layer,
     * passing the resolved boolean down to {@link TraceSymbolicationService}.
     */
    @GET
    @Path("symbolicate")
    public Response symbolicate(@QueryParam("symbol") String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("symbol parameter is required"))
                    .build();
        }

        boolean internal = isInternal();
        return symbolicationService
                .symbolicate(symbol, internal)
                .map(meta -> Response.ok(meta).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse("Symbol not found in allowlist"))
                        .build());
    }

    /**
     * Lists recorded execution runs for a session.
     *
     * <p>Enforces session ownership: unowned sessions return 404 to avoid disclosing existence.
     * Internal operators inspecting another user's session emit a mandatory audit record.
     */
    @GET
    @Path("sessions/{sessionId}/runs")
    public Response listRuns(
            @PathParam("sessionId") String sessionId,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        Response authCheck = authorizeSessionAccess(sessionId);
        if (authCheck != null) {
            return authCheck;
        }

        if (traceStoreInstance == null || traceStoreInstance.isUnsatisfied()) {
            return Response.ok(List.of()).build();
        }

        int clampedLimit = (int) Math.min(Math.max(1, limit), LIST_RUNS_MAX);
        int nonNegativeOffset = Math.max(0, offset);

        List<ExecutionRunSummaryDto> runs =
                traceStoreInstance.get().findRunsBySessionId(sessionId, nonNegativeOffset, clampedLimit);
        return Response.ok(runs).build();
    }

    /**
     * Retrieves recorded execution trace events for replay and scrubbing.
     *
     * <p>Results are bounded to a maximum of 500 records per page.
     */
    @GET
    @Path("runs/{runId}/trace")
    public Response getRunTrace(
            @PathParam("runId") String runId,
            @QueryParam("fromSequence") Long fromSequence,
            @QueryParam("toSequence") Long toSequence,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("200") int limit) {
        String sessionId = extractSessionId(runId);
        Response authCheck = authorizeSessionAccess(sessionId);
        if (authCheck != null) {
            return authCheck;
        }

        if (traceStoreInstance == null || traceStoreInstance.isUnsatisfied()) {
            return Response.ok(List.of()).build();
        }

        int clampedLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        int nonNegativeOffset = Math.max(0, offset);
        long startSeq = fromSequence != null ? Math.max(0L, fromSequence) : 0L;
        // fromSequence and toSequence are sequence indices; ensure toSequence is non-negative when present.
        Long endSeq = toSequence != null ? Math.max(0L, toSequence) : null;

        List<ExecutionTraceDto> traces =
                traceStoreInstance.get().findByRunIdBounded(runId, startSeq, endSeq, nonNegativeOffset, clampedLimit);
        return Response.ok(traces).build();
    }

    private Response authorizeSessionAccess(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionCatalogStore == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("Session not found"))
                    .build();
        }

        Optional<SessionCatalogEntry> entryOpt = sessionCatalogStore.bySessionId(sessionId);
        if (entryOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("Session not found: " + sessionId))
                    .build();
        }

        String callerUserId = resolveUserId();
        SessionCatalogEntry entry = entryOpt.get();
        // ownerKey is persisted at session creation from the same identity source that
        // resolveUserId() reads back: securityContext.getUserPrincipal().getName() for REST
        // (see AgentResource / ApiKeyAuthFilter) or the web session userId. The formats therefore
        // align within each identity domain. A null principal falls back to
        // WorkspaceContext.getUserIdOrUnknown() == "unknown", which collides with no real ownerKey,
        // so a missing identity fails closed (404) instead of granting access.
        if (!isOwner(entry, callerUserId)) {
            if (!isInternal()) {
                // Non-disclosure of unowned sessions
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse("Session not found: " + sessionId))
                        .build();
            }
            if (auditService != null) {
                try {
                    // Audit is a side-effect of an authorized internal inspection; an audit-store
                    // failure must not mask the read, so degrade gracefully.
                    auditService.recordMandatory(
                            sessionId,
                            callerUserId,
                            "operator",
                            SecurityEventType.SESSION_INSPECTED,
                            "Privileged replay inspection of session " + sessionId);
                } catch (Exception auditEx) {
                    log.warnf(
                            auditEx,
                            "Cockpit: audit record for internal inspection of session %s failed; "
                                    + "continuing with read",
                            sessionId);
                }
            }
        }
        return null;
    }

    /**
     * Extracts the owning sessionId from a logical runId following the convention
     * {@code runId = sessionId + ":turn-" + turnNumber}.
     *
     * <p>If {@code runId} does not follow the standard {@code :turn-} convention, this
     * method intentionally falls back to returning the full {@code runId}. This causes
     * downstream {@code sessionCatalogStore.bySessionId(runId)} lookups to fail and return
     * {@code 404 NOT_FOUND}, safely failing closed against unresolvable or forged identifiers.
     */
    static String extractSessionId(String runId) {
        if (runId == null) {
            return "";
        }
        int idx = runId.lastIndexOf(":turn-");
        return idx > 0 ? runId.substring(0, idx) : runId;
    }

    String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        // Fallback identity for non-REST contexts. Returns "unknown" when unbound, which is
        // intentionally distinct from any persisted ownerKey (see isOwner / authorizeSessionAccess).
        return WorkspaceContext.getUserIdOrUnknown();
    }

    /**
     * Null-safe ownership check. {@code ownerKey} is persisted at session creation from the very
     * same identity source that {@link #resolveUserId()} resolves at read time, so a match means the
     * caller owns the session. Uses {@link Objects#equals} to tolerate a null caller/owner safely.
     */
    private static boolean isOwner(SessionCatalogEntry entry, String callerUserId) {
        return Objects.equals(entry.ownerKey(), callerUserId);
    }

    boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole(AccessRoles.INTERNAL);
    }
}
