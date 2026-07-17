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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;

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

    @Context
    SecurityContext securityContext;

    @GET
    @Path("/status")
    @Operation(summary = "Get current onboarding draft status and step")
    public Response status() {
        String ownerId = requireOwner();
        if (ownerId == null) {
            log.warnf("Onboarding /status denied: missing or invalid principal");
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        OnboardingDraftDto draft = coordinator.getOrCreateFirstRunDraft(ownerId);
        OnboardingStatusDto dto = new OnboardingStatusDto(
                OnboardingCoordinator.PLAN.indexOf(draft.currentStepId()) + 1,
                draft.currentStepId(),
                draft.selectedNonSecretIds().isEmpty()
                        ? null
                        : draft.selectedNonSecretIds().get(0),
                draft.selectedNonSecretIds().size() > 1
                        ? draft.selectedNonSecretIds().get(1)
                        : null,
                draft.state() != OnboardingDraftState.DRAFT);
        return Response.ok(dto).build();
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
        Optional<OnboardingDraftDto> committed = coordinator.commit(draftId);
        auditService.recordMandatory(
                "onboarding",
                ownerId,
                "system",
                SecurityEventType.ONBOARDING_COMPLETED,
                "action=commit_draft draftId=" + draftId);
        return committed
                .map(d -> Response.ok(d).build())
                .orElse(Response.status(409)
                        .entity(new AmshaErrorResponse("Conflict", "Commit failed"))
                        .build());
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

    private String requireOwner() {
        return securityContext.getUserPrincipal() != null
                ? securityContext.getUserPrincipal().getName()
                : null;
    }
}
