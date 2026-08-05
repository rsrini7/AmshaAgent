package com.amsha.app.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.mcp.transport.McpApprovalService;
import com.amsha.mcp.transport.McpConfigImportService;
import com.amsha.mcp.transport.McpConnectionException;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.mcp.transport.McpRevisionConflictException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** REST adapter for explicit, bounded browser MCP config imports. */
@ApplicationScoped
@Path("/mcp/import")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class McpImportResource {
    @Inject
    McpConfigImportService importService;

    @Inject
    McpManagementService managementService;

    @Context
    SecurityContext securityContext;

    @Context
    HttpHeaders httpHeaders;

    @ConfigProperty(name = "amsha.channel.web.require-auth", defaultValue = "false")
    boolean webAuthRequired;

    public static class ImportForm {
        @RestForm("file")
        FileUpload file;
    }

    public record ApplyRequest(String operationId, String name, Map<String, String> headers) {
        public Map<String, String> headersOrEmpty() {
            return headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    @POST
    @Path("/preview")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response preview(@MultipartForm ImportForm form) {
        try {
            if (form == null || form.file == null) return badRequest("MCP config file is required");
            if (form.file.size() > McpConfigImportService.MAX_UPLOAD_BYTES) {
                return badRequest("MCP config upload exceeds 256 KiB");
            }
            byte[] content = Files.readAllBytes(form.file.uploadedFile());
            return Response.ok(importService.preview(resolveUserId(), form.file.fileName(), content))
                    .build();
        } catch (IOException exception) {
            return badRequest("MCP config file could not be read");
        } catch (IllegalArgumentException exception) {
            return badRequest(exception.getMessage());
        } catch (McpRevisionConflictException exception) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new AmshaErrorResponse("MCP revision conflict", exception.getMessage()))
                    .build();
        } catch (McpConnectionException exception) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new AmshaErrorResponse("MCP import apply failed", exception.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/apply")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response apply(ApplyRequest request, @HeaderParam("If-MCP-Revision") Long expectedRevision) {
        try {
            if (request == null || request.operationId() == null || request.name() == null) {
                return badRequest("import operationId and name are required");
            }
            McpConfigImportService.ImportEntry entry =
                    importService.consume(resolveUserId(), request.operationId(), request.name());
            if (!entry.supported()) return badRequest("MCP import entry rejected: " + entry.reason());
            String userId = resolveUserId();
            boolean existing = managementService.list(userId).stream()
                    .anyMatch(connection -> connection.name().equals(entry.name()));
            McpApprovalService.PendingApproval approval = existing
                    ? managementService.requestReconfigure(
                            userId, entry.name(), entry.endpoint(), request.headersOrEmpty(), expectedRevision)
                    : managementService.requestConnect(
                            userId, entry.name(), entry.endpoint(), request.headersOrEmpty());
            return Response.status(Response.Status.ACCEPTED).entity(approval).build();
        } catch (IllegalArgumentException exception) {
            return badRequest(exception.getMessage());
        }
    }

    private String resolveUserId() {
        String userId = McpRestUserIdentity.resolve(securityContext, httpHeaders, webAuthRequired);
        if (userId == null || userId.isBlank() || "unknown".equalsIgnoreCase(userId)) {
            throw new IllegalArgumentException("authenticated user is required");
        }
        return userId;
    }

    private static Response badRequest(String detail) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new AmshaErrorResponse("Invalid MCP import", detail))
                .build();
    }
}
