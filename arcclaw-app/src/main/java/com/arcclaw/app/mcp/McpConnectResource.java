package com.arcclaw.app.mcp;

import java.util.Map;
import java.util.stream.Collectors;

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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.mcp.config.McpToolAllowlist;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.transport.McpClientService;
import com.arcclaw.mcp.transport.McpConnectionException;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * REST admin endpoint for managing outbound MCP client connections at runtime.
 *
 * POST   /api/v1/mcp/connect          → connect to a remote HTTP/SSE MCP server
 * DELETE /api/v1/mcp/connect/{name}   → disconnect and deregister its tools
 * GET    /api/v1/mcp/connect          → list all currently connected remote servers
 *
 * All endpoints require X-ArcClaw-API-Key (protected by ApiKeyAuthFilter).
 *
 * Self-connect smoke test (no external server needed):
 *   POST with {"name":"self","url":"http://localhost:8080/mcp"}
 *   ArcClaw connects to itself - github and memory_search appear as dynamic tools.
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

    // ── request / response records ────────────────────────────────────────────

    public record ConnectRequest(@NotBlank String name, @NotBlank String url) {}

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
        if (!config.serverEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ArcClawErrorResponse("MCP server is disabled", "enable arcclaw.mcp.server-enabled"))
                    .build();
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ArcClawErrorResponse("name is required"))
                    .build();
        }
        if (request.url() == null || request.url().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ArcClawErrorResponse("url is required"))
                    .build();
        }

        long toolsBefore = registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(request.name()))
                .count();

        try {
            mcpClientService.connect(request.name(), request.url());
        } catch (McpConnectionException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new ArcClawErrorResponse("MCP handshake failed", e.getMessage()))
                    .build();
        }

        long toolsAfter = registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(request.name()))
                .count();

        return Response.ok(new ConnectResponse(
                request.name(),
                request.url(),
                (int) (toolsAfter - toolsBefore),
                "connected"
        )).build();
    }

    /**
     * Disconnect a remote server by name and deregister all its tools.
     * After this call, any agent invocation of those tools will throw McpToolNotFoundException.
     */
    @DELETE
    @Path("/{name}")
    @Operation(summary = "Disconnect an MCP server and deregister its tools")
    public Response disconnect(@PathParam("name") String name) {
        long toolsBefore = registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(name))
                .count();

        if (toolsBefore == 0) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ArcClawErrorResponse("No connected server found", "name: " + name))
                    .build();
        }

        // Deregister all tools from this server
        registry.getAll().stream()
                .filter(t -> t.mcpServerName().equals(name))
                .map(t -> t.toolName())
                .collect(Collectors.toList())  // collect first to avoid ConcurrentModificationException
                .forEach(registry::deregister);

        return Response.ok(new DisconnectResponse(name, true, (int) toolsBefore)).build();
    }

    /**
     * List all remote servers currently connected (inferred from registered dynamic tools).
     * Groups tools by mcpServerName - each unique name is one connected server.
     */
    @GET
    @Operation(summary = "List all currently connected remote MCP servers")
    public Response listConnected() {
        var grouped = registry.getAll().stream()
                .collect(Collectors.groupingBy(
                        t -> t.mcpServerName(),
                        Collectors.counting()
                ));

        var entries = grouped.entrySet().stream()
                .map(e -> new ServerEntry(e.getKey(), "(url not stored)", e.getValue()))
                .collect(Collectors.toList());

        return Response.ok(Map.of(
                "connectedServers", entries,
                "totalDynamicTools", registry.getAll().size()
        )).build();
    }
}
