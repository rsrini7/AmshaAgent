package com.amsha.app.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.util.Map;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.mcp.transport.McpSetupTokenService;

import org.eclipse.microprofile.openapi.annotations.Operation;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** Redeems the short-lived setup handoff issued by chat/channel surfaces. */
@Path("/mcp/setup")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class McpSetupResource {

    @Inject
    McpSetupTokenService setupTokenService;

    @Context
    SecurityContext securityContext;

    @POST
    @Path("/issue")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Issue a short-lived MCP setup handoff")
    public Response issue(IssueRequest request) {
        if (request == null
                || request.serverName() == null
                || request.serverName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("serverName is required"))
                    .build();
        }
        String userId = resolveUserId();
        String token = setupTokenService.issue(
                userId, request.channelTypeOrDefault(), request.channelIdOrDefault(), request.serverName());
        return Response.ok(Map.of("token", token, "path", "/#/mcp/setup?token=" + token))
                .build();
    }

    @POST
    @Path("/redeem")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Redeem a single-use MCP setup handoff")
    public Response redeem(RedeemRequest request) {
        String userId = resolveUserId();
        if (request == null || request.token() == null || request.token().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("token is required"))
                    .build();
        }
        return setupTokenService
                .redeem(request.token(), userId, request.channelType(), request.channelId())
                .<Response>map(grant -> Response.ok(new RedeemResponse(
                                grant.serverName(), grant.channelType(), grant.channelId(), grant.expiresAt()))
                        .build())
                .orElseGet(() -> Response.status(Response.Status.FORBIDDEN)
                        .entity(new AmshaErrorResponse("Invalid, expired, or user-mismatched setup token"))
                        .build());
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    public record IssueRequest(String serverName, String channelType, String channelId) {
        public IssueRequest(String serverName, String channelType) {
            this(serverName, channelType, null);
        }

        String channelTypeOrDefault() {
            return channelType == null || channelType.isBlank() ? "web" : channelType;
        }

        String channelIdOrDefault() {
            return channelId == null || channelId.isBlank() ? "web" : channelId;
        }
    }

    public record RedeemRequest(String token, String channelType, String channelId) {
        public RedeemRequest(String token) {
            this(token, null, null);
        }
    }

    public record RedeemResponse(String serverName, String channelType, String channelId, Instant expiresAt) {}
}
