package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.routing.ChatModelPool;
import com.amsha.onboarding.dto.ApprovalResolutionRequestDto;
import com.amsha.onboarding.dto.ConnectionValidationRequestDto;
import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.onboarding.dto.OnboardingStepDescriptorDto;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;

/**
 * Onboarding resource. The durable draft (via {@link OnboardingCoordinator}) is the single source of
 * truth; the legacy Caffeine-backed session registry and numbered-step wizard have been removed. All
 * reads/mutations authorize the authenticated owner; no anonymous or synthetic-owner state exists.
 */
@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class OnboardingResource {

    public static record StepUpdateRequest(List<String> selectedNonSecretIds) {}

    private static final Logger log = Logger.getLogger(OnboardingResource.class);

    @Inject
    OnboardingCoordinator coordinator;

    @Inject
    AuditService auditService;

    @Inject
    ChatModelPool modelPool;

    @Inject
    LlmProfileStorePort profileStore;

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Inject
    LlmConfigurationProvenance configurationProvenance;

    @Inject
    OnboardingStepDescriptorService descriptorService;

    @Inject
    OnboardingApprovalService approvalService;

    @Context
    SecurityContext securityContext;

    @Context
    HttpServerRequest request;

    @Context
    HttpHeaders httpHeaders;

    @ConfigProperty(name = "amsha.ui.skip-onboarding", defaultValue = "false")
    boolean skipOnboarding;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalApiKey;

    @GET
    @Path("/status")
    @Operation(summary = "Get current onboarding draft status and step")
    public Response status() {
        String ownerId = requireOwner();
        boolean authed = ownerId != null;
        // Before first-owner claim, unauthenticated status is limited to a loopback welcome preview.
        // Once claimed, status requires an owner; authenticated preconfigured status may signal skip.
        LlmConfigurationProvenance.Resolution configuration = configurationProvenance.resolve();
        boolean skip = skipOnboarding || configuration.ready();
        boolean unclaimed =
                bootstrapStateStore.getState().map(state -> state.isUnclaimed()).orElse(true);
        if (!authed && (!unclaimed || !OnboardingResourceHelper.isLoopbackRequest(request))) {
            log.warnf("Onboarding /status denied: missing or invalid principal");
            return Response.status(401)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }

        OnboardingStatusDto dto;
        if (authed) {
            OnboardingDraftDto draft = coordinator.getOrCreateFirstRunDraft(ownerId);
            dto = new OnboardingStatusDto(
                    coordinator.effectivePlan().indexOf(draft.currentStepId()) + 1,
                    draft.currentStepId(),
                    draft.selectedNonSecretIds().isEmpty()
                            ? null
                            : draft.selectedNonSecretIds().get(0),
                    draft.selectedNonSecretIds().size() > 1
                            ? draft.selectedNonSecretIds().get(1)
                            : null,
                    draft.state() != OnboardingDraftState.DRAFT,
                    skip,
                    configuration.vaultPriority(),
                    configuration.effectiveProvider(),
                    configuration.effectiveModel(),
                    configuration.effectiveBaseUrl(),
                    configuration.effectiveSource(),
                    configuration.credentialState(),
                    configuration.credentialSource());
        } else {
            // Before first-owner claim, expose only the non-secret wizard/configuration state.
            // This avoids using an expected 401 as browser bootstrap control flow.
            dto = new OnboardingStatusDto(
                    1,
                    "welcome",
                    null,
                    null,
                    false,
                    false,
                    configuration.vaultPriority(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        Response.ResponseBuilder rb = Response.ok(dto);
        rb.header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (authed && skip) {
            // Mirror main: authenticate the UI for chat via the internal API-key cookie.
            rb.cookie(OnboardingResourceHelper.buildSkipCookie(request, internalApiKey));
        }
        return rb.build();
    }

    @GET
    @Path("/drafts")
    @Operation(summary = "Get the authenticated owner's active onboarding draft id (or empty)")
    public Response listDrafts() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /drafts denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        return Response.ok(coordinator
                        .findActive(ownerId)
                        .map(OnboardingDraftDto::draftId)
                        .orElse(""))
                .build();
    }

    @POST
    @Path("/drafts")
    @Operation(summary = "Create or resume the owner's first-run draft")
    public Response createDraft() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /drafts POST denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        log.infof("Onboarding draft created/resumed for owner=%s", ownerId);
        return Response.ok(coordinator.getOrCreateFirstRunDraft(ownerId)).build();
    }

    @GET
    @Path("/drafts/{draftId}")
    @Operation(summary = "Get a draft by id (owner-scoped)")
    public Response getDraft(@PathParam("draftId") String draftId) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /drafts/{draftId} GET denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Optional<OnboardingDraftDto> draft = coordinator.findById(draftId);
        if (draft.isEmpty() || !draft.get().ownerId().equals(ownerId)) {
            log.warnf("Onboarding draft not found or owner mismatch draftId=%s owner=%s", draftId, ownerId);
            return Response.status(404)
                    .entity(new AmshaErrorResponse("Not Found", "Draft not found"))
                    .build();
        }
        return Response.ok(draft.get()).build();
    }

    @PUT
    @Path("/drafts/{draftId}/steps/{stepId}")
    @Operation(summary = "Advance the draft to a target step (index-based plan guard)")
    public Response goToStep(
            @PathParam("draftId") String draftId, @PathParam("stepId") String stepId, StepUpdateRequest request) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /drafts/{draftId}/steps/{stepId} PUT denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Optional<OnboardingDraftDto> draft = coordinator.findById(draftId);
        if (draft.isEmpty() || !draft.get().ownerId().equals(ownerId)) {
            log.warnf("Onboarding draft not found or owner mismatch draftId=%s owner=%s", draftId, ownerId);
            return Response.status(404)
                    .entity(new AmshaErrorResponse("Not Found", "Draft not found"))
                    .build();
        }
        List<String> selections = (request != null) ? request.selectedNonSecretIds() : null;

        // Validate extension steps before advancing.
        if (selections != null && !selections.isEmpty()) {
            String extPayload = coordinator.extractExtensionPayload(selections, stepId);
            Optional<OnboardingValidationResult> validationResult =
                    coordinator.validateExtension(draftId, stepId, ownerId, draftId, extPayload);
            if (validationResult.isPresent() && !validationResult.get().valid()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new AmshaErrorResponse(
                                "Bad Request",
                                "Extension validation failed: "
                                        + validationResult.get().safeFieldErrors()))
                        .build();
            }
        }

        Optional<OnboardingDraftDto> moved;
        try {
            moved = coordinator.updateStep(draftId, stepId, selections);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Bad Request", e.getMessage()))
                    .build();
        }
        if (moved.isPresent()) {
            log.infof("Onboarding draft %s advanced to step=%s owner=%s", draftId, stepId, ownerId);
        } else {
            log.warnf("Onboarding step transition failed draftId=%s stepId=%s owner=%s", draftId, stepId, ownerId);
        }
        return moved.map(d -> Response.ok(d).build())
                .orElse(Response.status(409)
                        .entity(new AmshaErrorResponse("Conflict", "Step transition failed"))
                        .build());
    }

    @POST
    @Path("/drafts/{draftId}/commit")
    @Operation(summary = "Finalize the draft")
    public Response commitDraft(@PathParam("draftId") String draftId) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /drafts/{draftId}/commit denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Optional<OnboardingDraftDto> draft = coordinator.findById(draftId);
        if (draft.isEmpty() || !draft.get().ownerId().equals(ownerId)) {
            log.warnf("Onboarding draft not found or owner mismatch draftId=%s owner=%s", draftId, ownerId);
            return Response.status(404)
                    .entity(new AmshaErrorResponse("Not Found", "Draft not found"))
                    .build();
        }
        Optional<OnboardingDraftDto> committed;
        try {
            committed = coordinator.commitDraft(draftId);
        } catch (IllegalStateException e) {
            log.warnf("Onboarding draft commit failed draftId=%s owner=%s: %s", draftId, ownerId, e.getMessage());
            return Response.status(409)
                    .entity(new AmshaErrorResponse("Conflict", "Commit failed: " + e.getMessage()))
                    .build();
        }
        return committed
                .filter(d -> d.state() == OnboardingDraftState.COMPLETED)
                .map(d -> {
                    auditService.recordMandatory(
                            "onboarding",
                            ownerId,
                            "system",
                            SecurityEventType.ONBOARDING_COMPLETED,
                            "action=commit_draft draftId=" + draftId);
                    return Response.ok(d).build();
                })
                .orElse(Response.status(409)
                        .entity(new AmshaErrorResponse("Conflict", "Commit failed"))
                        .build());
    }

    @GET
    @Path("/health/routing")
    @Operation(summary = "Routing health: pooled model count and profile-route status")
    public Response routingHealth() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        boolean internal = isInternal();
        int count = internal ? modelPool.size() : modelPool.countByScope("USER", ownerId);
        return Response.ok(Map.of(
                        "pooledModelCount",
                        count,
                        "scopeType",
                        internal ? "SYSTEM" : "USER",
                        "scopeId",
                        internal ? "system" : ownerId,
                        "effectivePlan",
                        coordinator.effectivePlan(),
                        "status",
                        "ok"))
                .build();
    }

    @GET
    @Path("/export")
    @Operation(
            summary = "Export non-secret profile records (connections, profiles, routes) for the authenticated owner")
    public Response exportProfiles() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Map<String, Object> exported = coordinator.exportProfiles(ownerId, profileStore);
        return Response.ok(exported).build();
    }

    @POST
    @Path("/import")
    @Operation(
            summary =
                    "Import non-secret profile records into a reviewed draft. Connections are STAGED; review and commit are required.")
    public Response importProfiles(Map<String, Object> data) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        if (data == null) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("BadRequest", "Import data payload is required"))
                    .build();
        }
        try {
            OnboardingDraftDto draft = coordinator.importProfilesToDraft(ownerId, data, profileStore);
            return Response.ok(Map.of(
                            "imported",
                            true,
                            "draftId",
                            draft.draftId(),
                            "currentStepId",
                            draft.currentStepId(),
                            "status",
                            "DRAFT_PENDING_REVIEW"))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("BadRequest", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/drafts/discard")
    @Operation(summary = "Discard the owner's active draft (resumable cleanup)")
    public Response discard() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /drafts/discard denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        coordinator.findActive(ownerId).ifPresent(d -> {
            coordinator.discard(d.draftId(), ownerId);
            log.infof("Onboarding draft discarded draftId=%s owner=%s", d.draftId(), ownerId);
        });
        return Response.ok(Map.of("discarded", true)).build();
    }

    @GET
    @Path("/steps/descriptors")
    @Operation(summary = "List typed step descriptors for the effective onboarding plan")
    public Response listStepDescriptors() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Locale locale = resolveLocale();
        List<OnboardingStepDescriptorDto> descriptors =
                descriptorService.getEffectiveDescriptors(coordinator.effectivePlan(), locale);
        return Response.ok(descriptors).build();
    }

    @GET
    @Path("/steps/{stepId}/descriptor")
    @Operation(summary = "Get a single typed step descriptor")
    public Response getStepDescriptor(@PathParam("stepId") String stepId) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        Locale locale = resolveLocale();
        int order = coordinator.effectivePlan().indexOf(stepId) + 1;
        return descriptorService
                .getDescriptor(stepId, order > 0 ? order : 1, locale)
                .map(d -> Response.ok(d).build())
                .orElse(Response.status(404)
                        .entity(new AmshaErrorResponse("Not Found", "Descriptor not found"))
                        .build());
    }

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

    private Locale resolveLocale() {
        return OnboardingResourceHelper.resolveLocale(httpHeaders);
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
