package com.arcclaw.core.api;

import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class AgentResource {

    @Inject
    AgentSessionService sessionService;

    @POST
    public Response createSession() {
        return Response.status(Response.Status.CREATED)
                .entity(new CreateSessionResponse(sessionService.createSession()))
                .build();
    }

    @POST
    @Path("/{id}/run")
    public Response runAgentSync(@PathParam("id") String sessionId, @Valid RunAgentRequest request) {
        return sessionService.runSync(sessionId, request);
    }

    @POST
    @Path("/{id}/run-async")
    public void runAgentAsync(@PathParam("id") String sessionId, @Valid RunAgentRequest request,
            @Suspended AsyncResponse response) {
        sessionService.runAsync(sessionId, request, response);
    }

    @GET
    @Path("/{id}/status/{role}")
    public Response getSessionStatus(@PathParam("id") String sessionId, @PathParam("role") String role) {
        return sessionService.getStatus(sessionId, role);
    }

    @DELETE
    @Path("/{id}")
    public Response terminateSession(@PathParam("id") String sessionId) {
        return sessionService.terminateSession(sessionId);
    }

    @POST
    @Path("/{id}/approve")
    public Response approveSession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
        return sessionService.approve(sessionId, agentRole);
    }

    @POST
    @Path("/{id}/deny")
    public Response denySession(
            @PathParam("id") String sessionId,
            @QueryParam("agentRole") @DefaultValue("coordinator") String agentRole) {
        return sessionService.deny(sessionId, agentRole);
    }
}
