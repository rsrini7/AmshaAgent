package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.util.HttpConstants;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.onboarding.dto.NetworkProfilePolicyDto;
import com.amsha.onboarding.dto.NetworkProfileUpdateRequest;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.dto.SettingsDto;
import com.amsha.onboarding.dto.VoiceSettingsUpdateRequest;
import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    LlmConfigurationProvenance configurationProvenance;

    @Inject
    DomainEventBus eventBus;

    @Inject
    AuditService auditService;

    @Inject
    UserPreferenceService userPreferenceService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Instance<LlmProfileStorePort> profileStoreInstance;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalApiKey;

    @ConfigProperty(name = "amsha.ui.skip-onboarding", defaultValue = "false")
    boolean skipOnboarding;

    @Context
    SecurityContext securityContext;

    @Context
    HttpHeaders httpHeaders;

    private String requireOwner() {
        if (securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        String headerKey = httpHeaders.getHeaderString(HttpConstants.X_AMSHA_API_KEY);
        if (headerKey != null && headerKey.equals(internalApiKey)) {
            return AccessRoles.INTERNAL;
        }
        return null;
    }

    private boolean backendLlmConfigured() {
        return skipOnboarding || configurationProvenance.resolve().ready();
    }

    @GET
    @Operation(summary = "Get current system settings and LLM configuration")
    public SettingsDto getSettings(@HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key) {
        String owner = requireOwner();
        if (owner == null) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build());
        }
        LlmConfigurationProvenance.Resolution configuration = configurationProvenance.resolve();
        String prompt = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
        String displayPrompt = prompt.length() > 200 ? prompt.substring(0, 199) + "…" : prompt;
        boolean effectiveSkipOnboarding = backendLlmConfigured();
        String voice = userPreferenceService.getVoice(owner).orElse("");
        String speechOutputMode = userPreferenceService.getOutputMode(owner).orElse("");
        List<String> availableVoices = userPreferenceService.getAvailableVoices();
        return new SettingsDto(
                configuration.effectiveProvider(),
                configuration.effectiveModel(),
                displayPrompt,
                vault.systemKeyExists(VaultConstants.LLM_API_KEY),
                appVersion,
                effectiveSkipOnboarding,
                configuration.vaultPriority(),
                configuration.effectiveBaseUrl(),
                configuration.effectiveSource(),
                configuration.credentialState(),
                configuration.credentialSource(),
                voice,
                speechOutputMode,
                availableVoices);
    }

    @PATCH
    @Path("/voice")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update user voice and speech output mode preferences")
    public Response updateVoice(
            @HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key, VoiceSettingsUpdateRequest req) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        if (req != null) {
            try {
                userPreferenceService.updatePreferences(owner, req.voice(), req.speechOutputMode());
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                        .entity(new AmshaErrorResponse("Invalid voice or speech setting", e.getMessage()))
                        .build();
            }
        }
        return Response.ok(Map.of(
                        "voice",
                        userPreferenceService.getEffectiveVoice(owner).orElse("default"),
                        "speechOutputMode",
                        userPreferenceService.getEffectiveOutputMode(owner).orElse("TEXT_ONLY")))
                .build();
    }

    @GET
    @Path("/prompt")
    @Operation(summary = "Get the full system prompt from vault")
    public Response getPrompt(@HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key) {
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
    public Response updateProvider(@HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key, ProviderUpdateRequest req) {
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
        // Mirrors the wizard's finalize path: stage the selection into the owner's draft,
        // scrub/store the credential as a vault ref, then commit so the same LLM_* system
        // vault keys the runtime honors (under vault-priority) are written durably.
        OnboardingDraftDto draft = coordinator.getOrCreateFirstRunDraft(owner);
        String defaultModel = req.model() != null && !req.model().isBlank()
                ? req.model()
                : ModelDefaults.defaultModelForProvider(req.provider());

        // Persist the credential at system scope (no workspace context on this REST path).
        // Mirrors activateLlmSettings, which the runtime reads under vault-priority.
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            vault.storeSystemKey(VaultConstants.LLM_API_KEY, req.apiKey().getBytes(StandardCharsets.UTF_8));
        }

        String payloadJson = String.format(
                "{\"connections\":[{\"provider\":\"%s\",\"modelId\":\"%s\",\"contextWindow\":8192,\"structuredOutputMode\":\"NATIVE\",\"capabilities\":[\"tool-calling\"]}],\"concernRoutes\":{\"agentLoop\":[0],\"compaction\":[0],\"classifier\":[0],\"embedding\":[0]}}",
                req.provider(), defaultModel);

        Optional<OnboardingDraftDto> updated =
                coordinator.updateStep(draft.draftId(), "connections", List.of(payloadJson));
        if (updated.isEmpty()) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("Failed to stage provider selection"))
                    .build();
        }
        // Mark the wizard as effectively complete so the coordinator's commit path is valid,
        // then commit to activate the LLM settings into the system vault.
        coordinator.moveToStep(updated.get().draftId(), "review");
        Optional<OnboardingDraftDto> committed =
                coordinator.commitDraft(updated.get().draftId());
        if (committed.isEmpty() || committed.get().state() != OnboardingDraftState.COMPLETED) {
            return Response.status(409)
                    .entity(new AmshaErrorResponse("Conflict", "Provider commit failed"))
                    .build();
        }

        auditService.recordMandatory(
                "settings",
                owner,
                "system",
                SecurityEventType.SETTINGS_CHANGED,
                "field=provider committed provider=" + req.provider());
        return Response.ok(Map.of("committed", true, "provider", req.provider()))
                .build();
    }

    @GET
    @Path("/connection-network-profile")
    @Operation(summary = "Get the network profile for a connection in the active draft or committed configuration")
    public Response getConnectionNetworkProfile(
            @HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key,
            @QueryParam("connectionIndex") @DefaultValue("-1") int connectionIndex) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        Optional<OnboardingDraftDto> activeDraft = coordinator.findActive(owner);
        if (activeDraft.isPresent()) {
            String profileJson = OnboardingResourceHelper.extractNetworkProfile(
                    activeDraft.get().selectedNonSecretIds(), connectionIndex, objectMapper);
            if (profileJson != null && !profileJson.isBlank()) {
                return Response.ok(NetworkProfilePolicyDto.fromJson(profileJson))
                        .build();
            }
        }
        if (profileStoreInstance != null && profileStoreInstance.isResolvable()) {
            List<LlmConnectionDto> conns = profileStoreInstance.get().listConnectionsByCreatedBy(owner);
            int idx = connectionIndex == -1 ? 0 : connectionIndex;
            if (conns != null && idx >= 0 && idx < conns.size()) {
                String profileJson = conns.get(idx).networkProfileJson();
                if (profileJson != null && !profileJson.isBlank()) {
                    return Response.ok(NetworkProfilePolicyDto.fromJson(profileJson))
                            .build();
                }
            }
        }
        return Response.ok(NetworkProfilePolicyDto.defaultPolicy()).build();
    }

    @PATCH
    @Path("/connection-network-profile")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Persist a per-connection network profile into the owner's active draft")
    public Response updateConnectionNetworkProfile(
            @HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key, NetworkProfileUpdateRequest req) {
        String owner = requireOwner();
        if (owner == null) {
            return Response.status(401)
                    .entity(new AmshaErrorResponse("Unauthorized", "Invalid or missing API Key"))
                    .build();
        }
        if (req == null) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("Request body is required"))
                    .build();
        }
        String profileJson;
        if (req.profile() != null) {
            String validationError = req.profile().validate();
            if (validationError != null) {
                return Response.status(400)
                        .entity(new AmshaErrorResponse(validationError))
                        .build();
            }
            profileJson = req.profile().toJson(objectMapper);
        } else if (req.networkProfileJson() != null && !req.networkProfileJson().isBlank()) {
            NetworkProfilePolicyDto dto;
            try {
                dto = objectMapper.readValue(req.networkProfileJson(), NetworkProfilePolicyDto.class);
            } catch (Exception e) {
                return Response.status(400)
                        .entity(new AmshaErrorResponse("networkProfileJson is malformed: " + e.getMessage()))
                        .build();
            }
            String validationError = dto.validate();
            if (validationError != null) {
                return Response.status(400)
                        .entity(new AmshaErrorResponse(validationError))
                        .build();
            }
            profileJson = dto.toJson(objectMapper);
        } else {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("Either profile or networkProfileJson must be provided"))
                    .build();
        }

        // Audit-before-mutation (§7.13.4): record mandatory audit before state transition
        auditService.recordMandatory(
                "settings",
                owner,
                "system",
                SecurityEventType.SETTINGS_CHANGED,
                "field=network-profile connectionIndex=" + req.connectionIndex());

        OnboardingDraftDto draft =
                coordinator.findActive(owner).orElseGet(() -> coordinator.getOrCreateFirstRunDraft(owner));
        Optional<OnboardingDraftDto> updated =
                coordinator.updateConnectionNetworkProfile(draft.draftId(), req.connectionIndex(), profileJson);
        StoreWrite storeWrite = updateCommittedConnectionProfile(owner, updated, req, profileJson);
        if (storeWrite == StoreWrite.CONFLICT) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new AmshaErrorResponse("Conflict", "Connection revision conflict"))
                    .build();
        }
        if (updated.isEmpty() && storeWrite != StoreWrite.UPDATED) {
            return Response.status(400)
                    .entity(new AmshaErrorResponse("No connections selection found for this draft or user"))
                    .build();
        }
        return Response.ok(Map.of("updated", true, "draftId", draft.draftId())).build();
    }

    /** Outcome of mirroring a draft profile edit onto the committed profile store. */
    private enum StoreWrite {
        UPDATED,
        SKIPPED,
        CONFLICT
    }

    /**
     * Mirrors a draft-scoped network-profile edit onto its committed profile-store row. The row is
     * located by the staged connection's explicit {@code id} and must be owned by the caller.
     * Wizard-staged connections carry no id and have no committed row, so they are draft-only;
     * there is deliberately no positional fallback (an index into the committed list cannot
     * identify the staged connection and rewrote unrelated rows once imports made stores
     * multi-row).
     *
     * @return how the committed-store mirror resolved
     */
    private StoreWrite updateCommittedConnectionProfile(
            String owner, Optional<OnboardingDraftDto> updated, NetworkProfileUpdateRequest req, String profileJson) {
        if (profileStoreInstance == null || !profileStoreInstance.isResolvable()) {
            return StoreWrite.SKIPPED;
        }
        String stagedId = updated.map(u -> OnboardingResourceHelper.extractConnectionId(
                        u.selectedNonSecretIds(), req.connectionIndex(), objectMapper))
                .orElse(null);
        if (stagedId == null) {
            return StoreWrite.SKIPPED;
        }
        Optional<LlmConnectionDto> match = profileStoreInstance.get().findConnection(stagedId);
        if (match.isEmpty() || !owner.equals(match.get().createdByUserId())) {
            return StoreWrite.SKIPPED;
        }
        return writeConnectionProfile(match.get(), profileJson) ? StoreWrite.UPDATED : StoreWrite.CONFLICT;
    }

    /**
     * Replaces a committed connection's network profile, preserving optimistic locking.
     * Returns false instead of throwing when the row lost a revision race; the caller maps
     * that to HTTP 409.
     */
    private boolean writeConnectionProfile(LlmConnectionDto existing, String profileJson) {
        LlmConnectionDto updatedConn = new LlmConnectionDto(
                existing.id(),
                existing.scopeType(),
                existing.scopeId(),
                existing.createdByUserId(),
                existing.displayName(),
                existing.providerAdapterId(),
                existing.endpointProfileId(),
                existing.customBaseUrl(),
                existing.authScheme(),
                profileJson,
                existing.state(),
                existing.revision() + 1,
                existing.createdAt(),
                Instant.now());
        return profileStoreInstance.get().updateConnection(updatedConn, existing.revision());
    }

    @PATCH
    @Path("/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update the global system prompt (legacy compatibility read/write)")
    public Response updatePrompt(@HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key, PromptUpdateRequest req) {
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
    public Response reset(@HeaderParam(HttpConstants.X_AMSHA_API_KEY) String key) {
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
