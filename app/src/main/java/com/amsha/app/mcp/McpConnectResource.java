package com.amsha.app.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Map;
import java.util.function.Supplier;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.mcp.config.McpToolAllowlist;
import com.amsha.mcp.transport.McpApprovalService;
import com.amsha.mcp.transport.McpConnectionException;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.mcp.transport.McpRevisionConflictException;

import org.eclipse.microprofile.openapi.annotations.Operation;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** Thin REST adapter for the shared MCP management and approval services. */
@ApplicationScoped
@Path("/mcp/connect")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class McpConnectResource {
    @Inject
    McpToolAllowlist config;

    @Inject
    McpManagementService managementService;

    @Context
    SecurityContext securityContext;

    public record ConnectRequest(
            @NotBlank String name, @NotBlank String url, Map<String, String> headers, String approvalToken) {
        public ConnectRequest(String name, String url) {
            this(name, url, Map.of(), null);
        }

        public ConnectRequest(String name, String url, Map<String, String> headers) {
            this(name, url, headers, null);
        }

        public Map<String, String> headersOrEmpty() {
            return headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record DisconnectResponse(String name, boolean deregistered, int toolsRemoved) {}

    public record DiscoverRequest(String name, String url, Map<String, String> headers, String approvalToken) {
        public DiscoverRequest(String name, String url, Map<String, String> headers) {
            this(name, url, headers, null);
        }

        public Map<String, String> headersOrEmpty() {
            return headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record RotateHeadersRequest(Map<String, String> headers) {
        public Map<String, String> headersOrEmpty() {
            return headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Request approval and connect an MCP server")
    public Response connect(ConnectRequest request) {
        if (!config.serverEnabled()) return serviceUnavailable();
        if (request == null || isBlank(request.name())) return badRequest("name is required");
        if (isBlank(request.url())) return badRequest("url is required");
        try {
            if (!isBlank(request.approvalToken())) {
                return Response.ok(managementService.connectNewWithApproval(
                                resolveUserId(),
                                request.name(),
                                request.url(),
                                request.headersOrEmpty(),
                                request.approvalToken()))
                        .build();
            }
            return pending(managementService.requestConnect(
                    resolveUserId(), request.name(), request.url(), request.headersOrEmpty()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpRevisionConflictException e) {
            return conflict(e.getMessage());
        } catch (McpConnectionException e) {
            return gatewayError(e.getMessage());
        }
    }

    public Response disconnect(String name) {
        return disconnect(name, null);
    }

    @DELETE
    @Path("/{name}")
    @Operation(summary = "Disconnect an MCP server and remove its tools")
    public Response disconnect(@PathParam("name") String name, @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        try {
            McpManagementService.DisconnectResult result = expectedRevision == null
                    ? managementService.disconnectWithResult(resolveUserId(), name)
                    : managementService.disconnectWithResult(resolveUserId(), name, expectedRevision);
            if (!result.deregistered()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new AmshaErrorResponse("No connected server found", "name: " + name))
                        .build();
            }
            return Response.ok(new DisconnectResponse(name, true, result.toolsRemoved()))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpRevisionConflictException e) {
            return conflict(e.getMessage());
        }
    }

    @GET
    @Operation(summary = "List durable MCP connections for the current user")
    public Response listConnected() {
        return Response.ok(Map.of("connections", managementService.list(resolveUserId())))
                .build();
    }

    @POST
    @Path("/discover")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Request approval and discover MCP tools without persistence")
    public Response discover(DiscoverRequest request) {
        if (request == null || isBlank(request.name()) || isBlank(request.url())) {
            return badRequest("MCP name and url are required");
        }
        try {
            if (!isBlank(request.approvalToken())) {
                return Response.ok(managementService.discoverWithApproval(
                                resolveUserId(),
                                request.name(),
                                request.url(),
                                request.headersOrEmpty(),
                                request.approvalToken()))
                        .build();
            }
            return pending(managementService.requestDiscovery(
                    resolveUserId(), request.name(), request.url(), request.headersOrEmpty()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpConnectionException e) {
            return gatewayError(e.getMessage());
        }
    }

    @POST
    @Path("/approvals/{taskId}/approve")
    @Operation(summary = "Approve and execute a pending MCP lifecycle operation")
    public Response approve(@PathParam("taskId") String taskId) {
        try {
            return Response.ok(managementService.approve(resolveUserId(), taskId))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpRevisionConflictException e) {
            return conflict(e.getMessage());
        } catch (McpConnectionException e) {
            return gatewayError(e.getMessage());
        }
    }

    @POST
    @Path("/approvals/{taskId}/reject")
    @Operation(summary = "Reject a pending MCP lifecycle operation")
    public Response reject(@PathParam("taskId") String taskId) {
        try {
            boolean rejected = managementService.rejectApproval(resolveUserId(), taskId);
            return rejected
                    ? Response.ok(Map.of("taskId", taskId, "status", "rejected"))
                            .build()
                    : Response.status(Response.Status.NOT_FOUND).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpRevisionConflictException e) {
            return conflict(e.getMessage());
        }
    }

    private static Response conflict(String detail) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new AmshaErrorResponse("MCP revision conflict", detail))
                .build();
    }

    @POST
    @Path("/{name}/activate")
    @Operation(summary = "Request approval and reconnect an existing MCP server")
    public Response activate(
            @PathParam("name") String name,
            @HeaderParam("X-MCP-APPROVAL-TOKEN") String approvalToken,
            @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        if (!isBlank(approvalToken)) {
            return operation(() ->
                    managementService.connectWithApproval(resolveUserId(), name, approvalToken, expectedRevision));
        }
        return pendingOperation(() -> managementService.requestReconnect(resolveUserId(), name, expectedRevision));
    }

    @POST
    @Path("/{name}/pause")
    public Response pause(@PathParam("name") String name, @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        return operation(() -> managementService.pause(resolveUserId(), name, expectedRevision));
    }

    @POST
    @Path("/{name}/resume")
    public Response resume(@PathParam("name") String name, @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        return pendingOperation(() -> managementService.requestReconnect(resolveUserId(), name, expectedRevision));
    }

    @POST
    @Path("/{name}/reconnect")
    public Response reconnect(@PathParam("name") String name, @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        return pendingOperation(() -> managementService.requestReconnect(resolveUserId(), name, expectedRevision));
    }

    @GET
    @Path("/{name}/tools")
    public Response tools(@PathParam("name") String name) {
        try {
            return Response.ok(managementService.listTools(resolveUserId(), name))
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpConnectionException e) {
            return gatewayError(e.getMessage());
        }
    }

    @POST
    @Path("/{name}/tools/{toolName}/enable")
    public Response enableTool(
            @PathParam("name") String name,
            @PathParam("toolName") String toolName,
            @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        return pendingOperation(
                () -> managementService.requestEnableTool(resolveUserId(), name, toolName, expectedRevision));
    }

    @DELETE
    @Path("/{name}/tools/{toolName}")
    public Response disableTool(
            @PathParam("name") String name,
            @PathParam("toolName") String toolName,
            @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        return operation(() -> managementService.disableTool(resolveUserId(), name, toolName, expectedRevision));
    }

    @POST
    @Path("/{name}/rotate-headers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rotateHeaders(
            @PathParam("name") String name,
            RotateHeadersRequest request,
            @HeaderParam("X-MCP-APPROVAL-TOKEN") String approvalToken,
            @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        Map<String, String> headers = request == null ? Map.of() : request.headersOrEmpty();
        if (!isBlank(approvalToken)) {
            return operation(() -> managementService.rotateHeadersWithApproval(
                    resolveUserId(), name, headers, approvalToken, expectedRevision));
        }
        return pendingOperation(
                () -> managementService.requestRotateHeaders(resolveUserId(), name, headers, expectedRevision));
    }

    private Response operation(Supplier<McpManagementService.ConnectionSnapshot> action) {
        try {
            return Response.ok(action.get()).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpRevisionConflictException e) {
            return conflict(e.getMessage());
        } catch (McpConnectionException e) {
            return gatewayError(e.getMessage());
        }
    }

    private static Response pending(McpApprovalService.PendingApproval approval) {
        return Response.status(Response.Status.ACCEPTED).entity(approval).build();
    }

    private Response pendingOperation(Supplier<McpApprovalService.PendingApproval> action) {
        try {
            return pending(action.get());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (McpRevisionConflictException e) {
            return conflict(e.getMessage());
        }
    }

    private static Response badRequest(String detail) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new AmshaErrorResponse("Invalid MCP operation", detail))
                .build();
    }

    private static Response gatewayError(String detail) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(new AmshaErrorResponse("MCP operation failed", detail))
                .build();
    }

    private static Response serviceUnavailable() {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(new AmshaErrorResponse("MCP server disabled", "enable amsha.mcp.server-enabled"))
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }
}
