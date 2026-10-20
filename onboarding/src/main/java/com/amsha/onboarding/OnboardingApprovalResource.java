package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.onboarding.dto.ApprovalResolutionRequestDto;
import com.amsha.onboarding.dto.ConnectionValidationRequestDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.security.http.ApprovalReceiptIssuer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;

/**
 * Handles onboarding approval workflows: connection validation, HITL approval resolution,
 * and OAuth grant issuance. Split from {@link OnboardingResource} to stay within PMD's
 * 500-line class limit. All endpoints share the {@code /onboarding} base path and enforce
 * owner authentication via the same {@link SecurityContext} + internal-key pattern.
 */
@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class OnboardingApprovalResource {

    private static final Logger log = Logger.getLogger(OnboardingApprovalResource.class);

    @Inject
    OnboardingCoordinator coordinator;

    @Inject
    OnboardingApprovalService approvalService;

    @Inject
    Instance<ApprovalReceiptIssuer> approvalReceiptIssuerInstance;

    @Context
    SecurityContext securityContext;

    @Context
    HttpServerRequest request;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalApiKey;

    @POST
    @Path("/drafts/{draftId}/validate-connection")
    @Operation(summary = "Validate connection with tiered format check or HITL-gated network probe")
    public Response validateConnection(@PathParam("draftId") String draftId, ConnectionValidationRequestDto request) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Optional<OnboardingDraftDto> draft = coordinator.findById(draftId);
        if (draft.isEmpty() || !draft.get().ownerId().equals(ownerId)) {
            return Response.status(404)
                    .entity(new AmshaErrorResponse("Not Found", "Draft not found"))
                    .build();
        }
        ConnectionValidationRequestDto req =
                request != null ? request : new ConnectionValidationRequestDto(0, false, false);
        return Response.ok(approvalService.validateConnection(draft.get(), req)).build();
    }

    @POST
    @Path("/drafts/{draftId}/approvals/{approvalId}/resolve")
    @Operation(summary = "Approve or deny a pending onboarding validation approval")
    public Response resolveApproval(
            @PathParam("draftId") String draftId,
            @PathParam("approvalId") String approvalId,
            ApprovalResolutionRequestDto resolution) {
        String callerId = requireOwner();
        if (callerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        if (!isInternal()) {
            log.warnf("Onboarding approval resolution denied: operator authorization required (caller=%s)", callerId);
            return Response.status(403)
                    .entity(new AmshaErrorResponse("Forbidden", "Operator authorization required for HITL approval"))
                    .build();
        }
        Optional<OnboardingDraftDto> draft = coordinator.findById(draftId);
        if (draft.isEmpty()) {
            return Response.status(404)
                    .entity(new AmshaErrorResponse("Not Found", "Draft not found"))
                    .build();
        }
        if (draft.get().ownerId().equals(callerId)) {
            log.warnf("Onboarding self-approval denied: draft owner cannot approve own probe (caller=%s)", callerId);
            return Response.status(403)
                    .entity(new AmshaErrorResponse("Forbidden", "Draft owner cannot approve their own probe request"))
                    .build();
        }
        ApprovalResolutionRequestDto res =
                resolution != null ? resolution : new ApprovalResolutionRequestDto(false, "denied");
        return approvalService
                .resolveApproval(draft.get(), approvalId, res, callerId)
                .map(r -> Response.ok(r).build())
                .orElse(Response.status(404)
                        .entity(new AmshaErrorResponse("Not Found", "Approval not found"))
                        .build());
    }

    @POST
    @Path("/oauth/grant")
    @Operation(summary = "Issue a checkpoint-backed operation grant for provider OAuth token exchange")
    public Response issueOAuthGrant(Map<String, String> body) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        String providerId = body != null ? body.get("providerId") : null;
        String sessionId = body != null ? body.get("sessionId") : null;
        if (providerId == null || providerId.isBlank()) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("Bad Request", "providerId is required"))
                    .build();
        }
        if (sessionId == null || sessionId.isBlank()) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse(
                            "Bad Request",
                            "sessionId is required: the grant is bound to the caller's session and "
                                    + "must not be derived silently"))
                    .build();
        }
        if (approvalReceiptIssuerInstance == null || !approvalReceiptIssuerInstance.isResolvable()) {
            return Response.status(503)
                    .entity(new AmshaErrorResponse("Service Unavailable", "ApprovalReceiptIssuer is unavailable"))
                    .build();
        }
        String targetHost =
                switch (providerId.trim().toLowerCase(Locale.ROOT)) {
                    case ModelDefaults.PROVIDER_CODEX -> "chatgpt.com";
                    case ModelDefaults.PROVIDER_CLAUDE_CODE, "claude_code" -> "api.anthropic.com";
                    case ModelDefaults.PROVIDER_AGY, "antigravity" -> "generativelanguage.googleapis.com";
                    case ModelDefaults.PROVIDER_KIMCHI -> "llm.kimchi.dev";
                    default -> "localhost";
                };
        try {
            String token = approvalReceiptIssuerInstance
                    .get()
                    .issueForOAuthExchange(ownerId, sessionId, providerId, targetHost);
            return Response.ok(Map.of("approvalToken", token, "targetHost", targetHost, "expiresInSeconds", 60))
                    .build();
        } catch (Exception e) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("Bad Request", "Failed to issue grant: " + LogUtil.rootMessage(e)))
                    .build();
        }
    }

    private String requireOwner() {
        return securityContext.getUserPrincipal() != null
                ? securityContext.getUserPrincipal().getName()
                : null;
    }

    private boolean isInternal() {
        return OnboardingResourceHelper.isInternal(securityContext, request, internalApiKey);
    }
}
