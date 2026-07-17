package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.routing.provider.NetworkProfilePolicy;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;
import com.amsha.onboarding.dto.NetworkProfileUpdateRequest;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.dto.SettingsDto;
import com.amsha.persistence.api.OnboardingDraftDto;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Settings resource. Reads reflect effective redacted state; mutations are delegated to the durable
 * onboarding draft coordinator and no longer write legacy system-vault LLM/prompt keys directly. A
 * reset is a scoped deactivation (discard the owner's draft) and never blindly deletes another
 * user's profiles or system keys.
 */
@Path("/settings")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class SettingsResource {

    @Inject
    SecretVault vault;

    @Inject
    OnboardingCoordinator coordinator;

    @Inject
    DomainEventBus eventBus;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "dev-key")
    String internalApiKey;

    @Context
    SecurityContext securityContext;

    @Context
    HttpHeaders httpHeaders;

    private String requireOwner() {
        if (securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        String headerKey = httpHeaders.getHeaderString("X-Amsha-API-Key");
        if (headerKey != null && headerKey.equals(internalApiKey)) {
            return "internal";
        }
        return null;
    }

    @GET
    @Operation(summary = "Get current system settings and LLM configuration")
    public SettingsDto getSettings(@HeaderParam("X-Amsha-API-Key") String key) {
        String owner = requireOwner();
        if (owner == null) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build());
        }
        String provider = readVaultString(VaultConstants.LLM_PROVIDER_KEY, "");
        String model = readVaultString(VaultConstants.LLM_MODEL_KEY, "");
        String prompt = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
        boolean hasKey = vault.systemKeyExists(VaultConstants.LLM_API_KEY);
        String displayPrompt = prompt.length() > 200 ? prompt.substring(0, 199) + "…" : prompt;
        return new SettingsDto(provider, model, displayPrompt, hasKey, appVersion);
    }

    @GET
    @Path("/prompt")
    @Operation(summary = "Get the full system prompt from vault")
    public Response getPrompt(@HeaderParam("X-Amsha-API-Key") String key) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        String prompt = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
        return Response.ok(Map.of("systemPrompt", prompt)).build();
    }

    @PATCH
    @Path("/provider")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Open an EDIT_PROFILE draft for provider change (no direct vault write)")
    public Response updateProvider(@HeaderParam("X-Amsha-API-Key") String key, ProviderUpdateRequest req) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        if (req.provider() == null || req.provider().isBlank()) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("provider is required"))
                    .build();
        }
        // Delegates to the durable draft; the actual credential/route commit follows validation,
        // HITL, review, and commit. No legacy LLM_* system-vault key is written here.
        OnboardingDraftDto draft = coordinator.getOrCreateFirstRunDraft(owner);
        String defaultModel =
                "groq".equals(req.provider()) ? "meta-llama/llama-4-scout-17b-16e-instruct" : "gpt-4o-mini";
        String payloadJson = String.format(
                "{\"connections\":[{\"provider\":\"%s\",\"modelId\":\"%s\",\"contextWindow\":8192,\"structuredOutputMode\":\"NATIVE\",\"capabilities\":[\"tool-calling\"]}],\"concernRoutes\":{\"agentLoop\":[0],\"compaction\":[0],\"classifier\":[0],\"embedding\":[0]}}",
                req.provider(), defaultModel);
        coordinator.updateStep(draft.draftId(), "connections", List.of(payloadJson));

        auditService.recordMandatory(
                "settings",
                owner,
                "system",
                SecurityEventType.SETTINGS_CHANGED,
                "field=provider draft=opened provider=" + req.provider());
        return Response.ok(Map.of("draft", "opened", "provider", req.provider()))
                .build();
    }

    @PATCH
    @Path("/connection-network-profile")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Persist a per-connection networkProfileJson into the owner's active draft")
    public Response updateConnectionNetworkProfile(
            @HeaderParam("X-Amsha-API-Key") String key, NetworkProfileUpdateRequest req) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        if (req == null
                || req.networkProfileJson() == null
                || req.networkProfileJson().isBlank()
                || !NetworkProfilePolicy.isValidJson(req.networkProfileJson())) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("networkProfileJson must be a valid supported network profile"))
                    .build();
        }
        OnboardingDraftDto draft =
                coordinator.findActive(owner).orElseGet(() -> coordinator.getOrCreateFirstRunDraft(owner));
        Optional<OnboardingDraftDto> updated = coordinator.updateConnectionNetworkProfile(
                draft.draftId(), req.connectionIndex(), req.networkProfileJson());
        if (updated.isEmpty()) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("No connections selection found for this draft"))
                    .build();
        }
        auditService.recordMandatory(
                "settings",
                owner,
                "system",
                SecurityEventType.SETTINGS_CHANGED,
                "field=network-profile connectionIndex=" + req.connectionIndex());
        return Response.ok(Map.of("updated", true, "draftId", draft.draftId())).build();
    }

    @PATCH
    @Path("/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update the global system prompt (legacy compatibility read/write)")
    public Response updatePrompt(@HeaderParam("X-Amsha-API-Key") String key, PromptUpdateRequest req) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        if (req.systemPrompt() == null || req.systemPrompt().isBlank()) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("systemPrompt is required"))
                    .build();
        }
        vault.storeSystemKey(
                VaultConstants.SYSTEM_PROMPT_KEY, req.systemPrompt().getBytes(StandardCharsets.UTF_8));
        auditService.recordMandatory(
                "settings",
                owner,
                "system",
                SecurityEventType.SETTINGS_CHANGED,
                "field=prompt length=" + req.systemPrompt().length());
        return Response.ok(Map.of("updated", true)).build();
    }

    @POST
    @Path("/reset")
    @Operation(summary = "Scoped deactivation: discard the owner's active draft (no blind key deletion)")
    public Response reset(@HeaderParam("X-Amsha-API-Key") String key) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        coordinator.findActive(owner).ifPresent(d -> coordinator.discard(d.draftId(), owner));
        auditService.recordMandatory(
                "settings", owner, "system", SecurityEventType.SETTINGS_RESET, "action=discard-active-draft");
        return Response.ok(Map.of("reset", true, "message", "Active onboarding draft discarded."))
                .build();
    }

    private String readVaultString(String key, String fallback) {
        return vault.retrieveSystemKey(key)
                .map(vk -> {
                    try (VaultKey k = vk) {
                        return new String(k.get(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}
