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
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.routing.ChatModelPool;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.util.HttpConstants;
import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.onboarding.spi.OnboardingValidationResult;
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
    SecretVault vault;

    @Context
    SecurityContext securityContext;

    @Context
    HttpServerRequest request;

    @ConfigProperty(name = "amsha.ui.skip-onboarding", defaultValue = "false")
    boolean skipOnboarding;

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String configuredProvider;

    @ConfigProperty(name = "amsha.llm.vault-priority", defaultValue = "false")
    boolean vaultPriority;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalApiKey;

    @GET
    @Path("/status")
    @Operation(summary = "Get current onboarding draft status and step")
    public Response status() {
        String ownerId = requireOwner();
        boolean authed = ownerId != null;
        // When the deployment is pre-configured (skip-onboarding or backend LLM already configured),
        // the status is reachable without an authenticated owner so the UI can skip the wizard.
        // Explicit skip remains supported, and environment-backed providers may skip the wizard.
        boolean skip = skipOnboarding || backendLlmConfigured();
        if (!authed && !skip) {
            log.warnf("Onboarding /status denied: missing or invalid principal");
            return Response.status(401)
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
                    vaultPriority);
        } else {
            // Pre-auth skip: report the deployment as already configured so the UI can go straight to chat.
            dto = new OnboardingStatusDto(0, "COMPLETE", configuredProvider, null, true, true, vaultPriority);
        }

        Response.ResponseBuilder rb = Response.ok(dto);
        if (skip) {
            // Mirror main: authenticate the UI for chat via the internal API-key cookie.
            rb.cookie(buildSkipCookie(internalApiKey));
        }
        return rb.build();
    }

    private boolean backendLlmConfigured() {
        if (skipOnboarding) {
            return true;
        }
        if (vaultPriority && vault.systemKeyExists(VaultConstants.LLM_API_KEY)) {
            return true;
        }
        String provider = configuredProvider != null ? configuredProvider.trim() : "";
        if (provider.isBlank()) {
            return false;
        }
        if (provider.equalsIgnoreCase("ollama")
                || provider.equalsIgnoreCase("claude-code")
                || provider.equalsIgnoreCase("omlx")
                || provider.equalsIgnoreCase("llamacpp")) {
            return true;
        }
        String apiKey = System.getProperty("AMSHA_LLM_API_KEY", System.getenv("AMSHA_LLM_API_KEY"));
        if (apiKey != null && !apiKey.isBlank()) {
            return true;
        }
        if (!vaultPriority && provider.equalsIgnoreCase("infinite-loop")) {
            String providers = System.getProperty(
                    "AMSHA_INFINITE_LOOP_LLM_PROVIDERS", System.getenv("AMSHA_INFINITE_LOOP_LLM_PROVIDERS"));
            return providers != null && !providers.isBlank();
        }
        return false;
    }

    private NewCookie buildSkipCookie(String rawKey) {
        return new NewCookie.Builder(HttpConstants.COOKIE_AMSHA_API_KEY)
                .value(rawKey)
                .path("/")
                .maxAge(3600 * 24 * 30)
                .httpOnly(true)
                .secure(isSecureCookieRequired())
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    private boolean isSecureCookieRequired() {
        if (request == null || request.remoteAddress() == null) {
            return false;
        }
        String host = request.remoteAddress().host();
        boolean loopback = HttpConstants.isLoopbackHost(host);
        String scheme = request.scheme();
        return !(loopback && "http".equalsIgnoreCase(scheme));
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
        Map<String, Object> health = Map.of(
                "pooledModelCount", modelPool.size(),
                "effectivePlan", coordinator.effectivePlan(),
                "status", "ok");
        return Response.ok(health).build();
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
                    "Import non-secret profile records. Connections are saved as STAGED; secrets must be supplied via a follow-up draft.")
    public Response importProfiles(Map<String, Object> data) {
        String ownerId = requireOwner();
        if (ownerId == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Authentication required"))
                    .build();
        }
        coordinator.importProfiles(ownerId, data, profileStore);
        return Response.ok(Map.of("imported", true)).build();
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
