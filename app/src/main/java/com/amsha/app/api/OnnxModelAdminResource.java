package com.amsha.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Duration;
import java.util.regex.Pattern;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.localml.onnx.OnnxSessionLeaseManager;
import com.amsha.localml.onnx.OnnxSessionLeaseManager.UnloadResult;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.RunOnVirtualThread;

/** Internal operator controls for loaded ONNX model sessions. */
@ApplicationScoped
@Path("/admin/local-ml/models")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class OnnxModelAdminResource {

    private static final Pattern BUNDLE_ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");

    @Inject
    OnnxSessionLeaseManager sessionLeaseManager;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "amsha.onnx.shutdown-grace", defaultValue = "PT10S")
    Duration shutdownGrace;

    @Context
    SecurityContext securityContext;

    /** Rejects new leases and cooperatively drains one loaded bundle before closing it. */
    @POST
    @Path("/{bundleId}/unload")
    public Response unload(@PathParam("bundleId") String bundleId) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Only internal API keys can unload ONNX models"))
                    .build();
        }
        if (!isValidBundleId(bundleId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("invalid_bundle_id", "Invalid ONNX bundle ID"))
                    .build();
        }

        auditService.recordMandatory(
                "local-ml-model:" + bundleId,
                resolveUserId(),
                "system",
                SecurityEventType.ONNX_MODEL_UNLOAD_REQUESTED,
                "bundleId=" + bundleId);

        UnloadResult result = sessionLeaseManager.unload(bundleId, shutdownGrace);
        ModelUnloadResponse payload = new ModelUnloadResponse(bundleId, result.name());
        return switch (result) {
            case UNLOADED -> Response.ok(payload).build();
            case NOT_LOADED ->
                Response.status(Response.Status.NOT_FOUND).entity(payload).build();
            case DRAINING ->
                Response.status(Response.Status.CONFLICT).entity(payload).build();
        };
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }

    private static boolean isValidBundleId(String bundleId) {
        return bundleId != null && BUNDLE_ID_PATTERN.matcher(bundleId).matches();
    }

    public record ModelUnloadResponse(String bundleId, String status) {}
}
