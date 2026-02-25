package com.arcclaw.core.api;

import com.arcclaw.core.api.dto.AuditEventResponse;
import com.arcclaw.core.audit.AuditEvent;
import com.arcclaw.core.audit.AuditEventRepository;
import com.arcclaw.core.audit.AuditVerifier;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditEventRepository auditEventRepository;

    @Inject
    AuditVerifier auditVerifier;

    @GET
    @Path("/session/{sessionId}")
    public Response getSessionAudit(@PathParam("sessionId") String sessionId) {
        List<AuditEvent> events = auditEventRepository.findBySessionId(sessionId);
        
        List<AuditEventResponse> responseRows = events.stream()
                .map(this::toResponse)
                .toList();

        return Response.ok(responseRows).build();
    }

    @GET
    @Path("/{sessionId}/verify")
    public Response verifySessionChain(@PathParam("sessionId") String sessionId) {
        AuditVerifier.VerificationResult result = auditVerifier.verifyChain(sessionId);
        return Response.ok(result).build();
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.id, e.sessionId, e.userId, e.agentRole, e.goalHash,
                e.outcome, e.eventType, e.eventDetail,
                e.inputTokens, e.outputTokens, e.usdCost, e.iterationsUsed,
                e.timestamp, e.hmacHash
        );
    }
}
