package com.arcclaw.app.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.stream.Collectors;

import com.arcclaw.app.ui.dto.AgentStatusDto;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.security.AutonomyLevel;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;

/**
 * Provides a read-only view of current agent states for the Lit UI.
 */
@ApplicationScoped
@Path("agents")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AgentStatusResource {

    @Inject
    AgentStateStore stateStore;

    @Inject
    SessionHeartbeatTracker sessionHeartbeatTracker;

    @Inject
    AgentSseService agentSseService;

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "Subscribe to live agent state change events via SSE")
    public Multi<AgentSseService.SseEvent> agentEvents(@QueryParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Multi.createFrom().failure(new WebApplicationException(Response.Status.BAD_REQUEST));
        }
        return agentSseService.subscribe(sessionId);
    }

    @GET
    @Operation(summary = "List all active agents and their current statuses")
    public List<AgentStatusDto> listAgents() {
        return stateStore.getAll().stream()
                .map(s -> {
                    long totalTokens = 0L;
                    if (s.sessionId() != null) {
                        long[] tokens = sessionHeartbeatTracker.getTokens(s.sessionId());
                        if (tokens != null && tokens.length >= 2) {
                            totalTokens = tokens[0] + tokens[1]; // input + output
                        }
                    }
                    return new AgentStatusDto(
                            s.name(),
                            s.name(),
                            s.status(),
                            s.currentTask(),
                            AutonomyLevel.SUPERVISED.name().toLowerCase(),
                            totalTokens // ← was 0L
                            );
                })
                .collect(Collectors.toList());
    }
}
