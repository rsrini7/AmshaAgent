package com.amsha.app.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.config.McpToolAllowlist;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.mcp.transport.McpConnectionException;
import com.amsha.mcp.transport.McpConnectionLifecycleService;

import org.eclipse.microprofile.openapi.annotations.Operation;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * REST admin endpoint for managing outbound MCP client connections at runtime.
 *
 * POST   /api/v1/mcp/connect          → connect to a remote HTTP/SSE MCP server
 * DELETE /api/v1/mcp/connect/{name}   → disconnect and deregister its tools
 * GET    /api/v1/mcp/connect          → list all currently connected remote servers
 *
 * All endpoints require X-Amsha-API-Key (protected by ApiKeyAuthFilter).
 *
 * Self-connect smoke test (no external server needed):
 *   POST with {"name":"self","url":"http://localhost:8080/mcp"}
 *   Amsha Agent connects to itself - github and recall_memory_search appear as dynamic tools.
 *   Then send /mcp in Telegram to confirm the live count.
 */
@ApplicationScoped
@Path("/mcp/connect")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class McpConnectResource {

    @Inject
    McpClientService mcpClientService;

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Inject
    McpToolAllowlist config;

    @Inject
    AuditService auditService;

    @Inject
    McpConnectionLifecycleService lifecycleService;

    @Context
    SecurityContext securityContext;

    // ── request / response records ────────────────────────────────────────────

    public record ConnectRequest(
            @NotBlank String name, @NotBlank String url, Map<String, String> headers) {
        public ConnectRequest(String name, String url) {
            this(name, url, Map.of());
        }

        public Map<String, String> headersOrEmpty() {
            return headers == null ? Map.of() : headers;
        }
    }

    public record ConnectResponse(String name, String url, int toolsRegistered, String status) {}

    public record DisconnectResponse(String name, boolean deregistered, int toolsRemoved) {}

    public record ServerEntry(String name, String url, long toolCount) {}

    // ── endpoints ─────────────────────────────────────────────────────────────

    /**
     * Connect to a remote HTTP/SSE MCP server.
     * Performs the full MCP handshake: initialize → notifications/initialized → tools/list.
     * All discovered tools are registered into DynamicToolCapabilityRegistry and become
     * immediately available to the agent loop via ExternalMcpToolAdapter.
     *
     * Body: {"name":"my-server","url":"http://remote-mcp-host:3000/mcp"}
     *
     * Self-connect test: {"name":"self","url":"http://localhost:8080/mcp"}
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Connect to a remote MCP server (HTTP/SSE)")
    public Response connect(ConnectRequest request) {
        String userId = resolveUserId();
        if (!config.serverEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new AmshaErrorResponse("MCP server is disabled", "enable amsha.mcp.server-enabled"))
                    .build();
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("name is required"))
                    .build();
        }
        if (request.url() == null || request.url().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("url is required"))
                    .build();
        }
        try {
            lifecycleService.validateHttpRegistration(request.url(), request.headersOrEmpty());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Invalid MCP registration", e.getMessage()))
                    .build();
        }

        long toolsBefore = registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(request.name()))
                .count();

        auditService.recordMandatory(
                "mcp-connect",
                userId,
                "system",
                SecurityEventType.MCP_CONNECTION_REQUESTED,
                "server=" + request.name() + " url=" + request.url());

        try {
            mcpClientService.connectForUser(
                    request.name(), userId, request.url(), ToolCategory.NETWORK, request.headersOrEmpty());
        } catch (McpConnectionException e) {
            auditService.recordMandatory(
                    "mcp-connect",
                    userId,
                    "system",
                    SecurityEventType.MCP_CONNECTION_VALIDATION_FAILED,
                    "server=" + request.name() + " reason=" + e.getMessage());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new AmshaErrorResponse("MCP handshake failed", e.getMessage()))
                    .build();
        }

        long toolsAfter = registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(request.name()))
                .count();

        int newTools = (int) (toolsAfter - toolsBefore);
        auditService.recordMandatory(
                "mcp-connect",
                userId,
                "system",
                SecurityEventType.MCP_SERVER_CONNECTED,
                "server=" + request.name() + " url=" + request.url() + " tools=" + newTools);
        try {
            var persisted = lifecycleService.persistSuccessfulHttp(
                    request.name(), request.url(), ToolCategory.NETWORK, userId, request.headersOrEmpty());
            mcpClientService.configureHeaderSecretRefs(request.name(), userId, persisted.headerSecretRefs());
        } catch (RuntimeException e) {
            mcpClientService.clearHttpConnectionState(request.name(), userId);
            throw e;
        }

        return Response.ok(new ConnectResponse(
                        request.name(), request.url(), (int) (toolsAfter - toolsBefore), "connected"))
                .build();
    }

    /**
     * Disconnect a remote server by name and deregister all its tools.
     * After this call, any agent invocation of those tools will throw McpToolNotFoundException.
     */
    @DELETE
    @Path("/{name}")
    @Operation(summary = "Disconnect an MCP server and deregister its tools")
    public Response disconnect(@PathParam("name") String name) {
        String userId = resolveUserId();
        long toolsBefore = registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(name))
                .count();

        boolean durableDeleted = lifecycleService.deleteByNameAndUserId(name, userId);
        mcpClientService.clearHttpConnectionState(name, userId);
        if (toolsBefore == 0 && !durableDeleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new AmshaErrorResponse("No connected server found", "name: " + name))
                    .build();
        }

        // Deregister all tools from this server
        registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(name))
                .map(t -> t.toolName())
                .collect(Collectors.toList()) // collect first to avoid ConcurrentModificationException
                .forEach(registry::deregister);

        auditService.recordMandatory(
                "mcp-disconnect",
                userId,
                "system",
                SecurityEventType.MCP_SERVER_DISCONNECTED,
                "server=" + name + " toolsRemoved=" + toolsBefore);

        return Response.ok(new DisconnectResponse(name, true, (int) toolsBefore))
                .build();
    }

    /**
     * List all remote servers currently connected (inferred from registered dynamic tools).
     * Groups tools by mcpServerName - each unique name is one connected server.
     */
    @GET
    @Operation(summary = "List all currently connected remote MCP servers")
    public Response listConnected() {
        String userId = resolveUserId();
        var grouped = registry.getAll().stream()
                .collect(Collectors.groupingBy(t -> t.mcpServerName(), Collectors.counting()));

        var durableEntries = lifecycleService.listByUserId(userId).stream()
                .map(connection -> new ServerEntry(
                        connection.name(), connection.endpoint(), grouped.getOrDefault(connection.name(), 0L)))
                .collect(Collectors.toList());

        var transientEntries = grouped.entrySet().stream()
                .filter(e -> durableEntries.stream()
                        .noneMatch(stored -> stored.name().equals(e.getKey())))
                .map(e -> {
                    var url = mcpClientService.httpServerUrl(e.getKey());
                    return new ServerEntry(e.getKey(), url == null ? null : url.orElse(null), e.getValue());
                })
                .toList();

        var entries = new ArrayList<ServerEntry>(durableEntries);
        entries.addAll(transientEntries);

        return Response.ok(Map.of(
                        "connectedServers",
                        entries,
                        "totalDynamicTools",
                        registry.getAll().size()))
                .build();
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }
}
