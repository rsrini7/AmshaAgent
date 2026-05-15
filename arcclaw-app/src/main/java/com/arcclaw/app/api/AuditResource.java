package com.arcclaw.app.api;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.api.AgentSessionService;
import com.arcclaw.core.api.dto.AuditEventResponse;
import com.arcclaw.core.audit.AuditVerifier;
import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.core.domain.model.AuditEventRecord;
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

    @GET
    @Path("/session/{sessionId}")
    @Operation(summary = "Retrieve all audit log events for a specific session")
    public Response getSessionAudit(@PathParam("sessionId") String sessionId) {
        var authError = sessionService.validateSession(sessionId);
        if (authError.isPresent()) return authError.get();

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
        var authError = sessionService.validateSession(sessionId);
        if (authError.isPresent()) return authError.get();

        AuditVerifier.VerificationResult result = auditVerifier.verifyChain(sessionId);
        return Response.ok(result).build();
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
