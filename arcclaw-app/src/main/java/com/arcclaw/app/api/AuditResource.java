package com.arcclaw.app.api;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.arcclaw.core.api.AgentSessionService;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.api.dto.AuditEventResponse;
import com.arcclaw.core.audit.AuditVerifier;
import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.core.domain.model.AuditEventRecord;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.persistence.api.AuditEventStorePort;
import org.eclipse.microprofile.openapi.annotations.Operation;

@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditEventStorePort auditStore;

    @Inject
    AuditVerifier auditVerifier;

    @Inject
    AgentSessionService sessionService;

    @Context
    SecurityContext securityContext;

    @GET
    @Path("/session/{sessionId}")
    @Operation(summary = "Retrieve all audit log events for a specific session")
    public Response getSessionAudit(@PathParam("sessionId") String sessionId) {
        var authError = sessionService.validateSession(sessionId, resolveUserId());
        if (authError.isPresent()) {
            var err = authError.get();
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ArcClawErrorResponse(err.errorMessage())).build();
        }

        var events = auditStore.findBySessionId(sessionId).stream()
                .map(AuditEventRecord::fromDto)
                .toList();

        List<AuditEventResponse> responseRows = events.stream()
                .map(this::toResponse)
                .toList();

        return Response.ok(responseRows).build();
    }

    @GET
    @Path("/{sessionId}/verify")
    @Operation(summary = "Verify the cryptographic HMAC chain integrity for a session's audit log")
    public Response verifySessionChain(@PathParam("sessionId") String sessionId) {
        var authError = sessionService.validateSession(sessionId, resolveUserId());
        if (authError.isPresent()) {
            var err = authError.get();
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ArcClawErrorResponse(err.errorMessage())).build();
        }

        AuditVerifier.VerificationResult result = auditVerifier.verifyChain(sessionId);
        return Response.ok(result).build();
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(), e.getSessionId(), e.getUserId(), e.getAgentRole(), e.getGoalHash(),
                e.getOutcome(), e.getEventType(), e.getEventDetail(),
                e.getInputTokens(), e.getOutputTokens(), e.getUsdCost(), e.getIterationsUsed(),
                e.getTimestamp(), e.getHmacHash()
        );
    }
}
