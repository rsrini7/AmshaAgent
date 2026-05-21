package com.arcclaw.app.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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

import com.arcclaw.core.api.AgentSessionService;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.api.dto.AuditEventResponse;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.AuditVerifier;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.core.domain.model.AuditEventRecord;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.persistence.api.AuditEventDto;
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
    @Path("/event-types")
    @Operation(summary = "List all valid SecurityEventType values for UI filters")
    public Response listEventTypes() {
        List<String> types = Arrays.stream(SecurityEventType.values())
                .map(Enum::name)
                .toList();
        return Response.ok(Map.of("eventTypes", types)).build();
    }

    @GET
    @Operation(summary = "List all audit events with pagination and optional filters")
    public Response listAuditEvents(
            @QueryParam("sessionId") String sessionId,
            @QueryParam("eventType") String eventType,
            @QueryParam("from") String fromStr,
            @QueryParam("to") String toStr,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {

        int limit = Math.min(200, Math.max(1, size));
        int offset = Math.max(0, page) * limit;

        Instant from = parseInstant(fromStr);
        Instant to = parseInstant(toStr);

        List<AuditEventDto> events = auditStore.findAll(sessionId, eventType, from, to, offset, limit);
        long total = auditStore.countAll(sessionId, eventType, from, to);

        List<AuditEventResponse> responseRows = events.stream()
            .map(this::toResponseFromDto)
            .toList();

        return Response.ok(Map.of(
            "events", responseRows,
            "total", total,
            "page", page,
            "size", limit
        )).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a single audit event by ID")
    public Response getAuditEvent(@PathParam("id") String id) {
        try {
            UUID uuid = UUID.fromString(id);
            return auditStore.findById(uuid)
                .map(dto -> Response.ok(toResponseFromDto(dto)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                    .entity(new ArcClawErrorResponse("Audit event not found: " + id))
                    .build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ArcClawErrorResponse("Invalid audit event ID: " + id))
                .build();
        }
    }

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

    private AuditEventResponse toResponseFromDto(AuditEventDto dto) {
        return new AuditEventResponse(
                dto.id(), dto.sessionId(), dto.userId(), dto.agentRole(), dto.goalHash(),
                dto.outcome() != null ? parseAuditOutcome(dto.outcome()) : null,
                dto.eventType() != null ? parseSecurityEventType(dto.eventType()) : null,
                dto.eventDetail(),
                dto.inputTokens(), dto.outputTokens(), dto.usdCost(), dto.iterationsUsed(),
                dto.timestamp(), dto.hmacHash()
        );
    }

    private AuditOutcome parseAuditOutcome(String value) {
        try {
            return AuditOutcome.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private SecurityEventType parseSecurityEventType(String value) {
        try {
            return SecurityEventType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
