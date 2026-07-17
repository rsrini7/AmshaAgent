package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Coordinator owning the durable onboarding draft lifecycle. It is the single source of truth: the
 * legacy Caffeine-backed wizard state is removed. The first authenticated draft creation confirms
 * the bootstrap CLAIMED transition for the same principal.
 */
@ApplicationScoped
public class OnboardingCoordinator {

    private static final Logger log = Logger.getLogger(OnboardingCoordinator.class);
    private static final Duration DRAFT_TTL = Duration.ofDays(7);
    static final List<String> PLAN = List.of(
            "welcome",
            "connections",
            "routes",
            "persona-routing",
            "mcp",
            "integrations",
            "advanced-settings",
            "memory",
            "security",
            "channels",
            "identity",
            "review",
            "complete");

    @Inject
    OnboardingDraftStorePort draftStore;

    @Inject
    BootstrapStateStorePort bootstrapStateStore;

    @Inject
    SecretVault vault;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Finds or creates the owner's first-run draft. On first creation it performs the
     * CREDENTIAL_ISSUED -> CLAIMED bootstrap transition for the authenticated owner.
     */
    public OnboardingDraftDto getOrCreateFirstRunDraft(String ownerId) {
        Optional<OnboardingDraftDto> existing = draftStore.findActiveByOwner(ownerId, Instant.now());
        if (existing.isPresent()) {
            return existing.get();
        }
        confirmBootstrapIfNeeded(ownerId);
        Instant now = Instant.now();
        OnboardingDraftDto draft = new OnboardingDraftDto(
                "draft_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                ownerId,
                OnboardingDraftPurpose.FIRST_RUN,
                "welcome->connections->routes->persona-routing->mcp->integrations->advanced-settings->memory->security->channels->identity->review->complete",
                "welcome",
                List.of(),
                List.of(),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                now.plus(DRAFT_TTL),
                now,
                now);
        if (!draftStore.save(draft)) {
            return draftStore.findActiveByOwner(ownerId, Instant.now()).orElse(draft);
        }
        return draft;
    }

    public Optional<OnboardingDraftDto> findActive(String ownerId) {
        return draftStore.findActiveByOwner(ownerId, Instant.now());
    }

    public Optional<OnboardingDraftDto> findById(String draftId) {
        return draftStore.findById(draftId);
    }

    /**
     * Advances the draft to the next eligible step (index-based over the persisted plan). Returns
     * the updated draft, or empty if already at the last step.
     */
    public Optional<OnboardingDraftDto> advanceStep(String draftId, String currentStepId) {
        int idx = PLAN.indexOf(currentStepId);
        if (idx < 0 || idx >= PLAN.size() - 1) {
            return draftStore.findById(draftId);
        }
        String next = PLAN.get(idx + 1);
        return moveToStep(draftId, next);
    }

    public Optional<OnboardingDraftDto> moveToStep(String draftId, String targetStepId) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty() || !PLAN.contains(targetStepId)) {
            return existing;
        }
        OnboardingDraftDto d = existing.get();
        List<String> completed = new ArrayList<>(d.completedStepIds());
        if (!completed.contains(d.currentStepId()) && !"welcome".equals(d.currentStepId())) {
            completed.add(d.currentStepId());
        }
        OnboardingDraftDto updated = new OnboardingDraftDto(
                d.draftId(),
                d.ownerId(),
                d.purpose(),
                d.planFingerprint(),
                targetStepId,
                completed,
                d.selectedNonSecretIds(),
                d.safeFieldErrors(),
                d.pendingApprovalIds(),
                d.state(),
                d.revision(),
                d.expiresAt(),
                d.createdAt(),
                Instant.now());
        if (draftStore.update(updated, d.revision())) {
            return Optional.of(updated);
        }
        return draftStore.findById(draftId);
    }

    /**
     * Atomically updates both the current step ID and the selections payload in a single database update.
     */
    public Optional<OnboardingDraftDto> updateStep(String draftId, String targetStepId, List<String> selections) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty() || !PLAN.contains(targetStepId)) return existing;
        OnboardingDraftDto d = existing.get();

        // Index-based transition checks
        int targetIdx = PLAN.indexOf(targetStepId);
        int connectionsIdx = PLAN.indexOf("connections");
        int routesIdx = PLAN.indexOf("routes");
        if (targetIdx > connectionsIdx
                && !d.completedStepIds().contains("connections")
                && !d.currentStepId().equals("connections")) {
            return Optional.empty(); // block skipping connections
        }
        if (targetIdx > routesIdx
                && !d.completedStepIds().contains("routes")
                && !d.currentStepId().equals("routes")) {
            return Optional.empty(); // block skipping routes
        }

        List<String> completed = new ArrayList<>(d.completedStepIds());
        if (!completed.contains(d.currentStepId()) && !"welcome".equals(d.currentStepId())) {
            completed.add(d.currentStepId());
        }

        List<String> processedSelections =
                processAndScrubSecrets(d.ownerId(), selections != null ? selections : d.selectedNonSecretIds());

        OnboardingDraftDto updated = new OnboardingDraftDto(
                d.draftId(),
                d.ownerId(),
                d.purpose(),
                d.planFingerprint(),
                targetStepId,
                completed,
                processedSelections,
                d.safeFieldErrors(),
                d.pendingApprovalIds(),
                d.state(),
                d.revision(),
                d.expiresAt(),
                d.createdAt(),
                Instant.now());
        if (draftStore.update(updated, d.revision())) {
            return Optional.of(updated);
        }
        return draftStore.findById(draftId);
    }

    private List<String> processAndScrubSecrets(String ownerId, List<String> selections) {
        if (selections == null || selections.isEmpty()) {
            return selections;
        }
        List<String> scrubbed = new ArrayList<>();
        for (String selection : selections) {
            try {
                Map<String, Object> payload = objectMapper.readValue(selection, Map.class);
                List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
                boolean modified = false;
                if (connections != null) {
                    for (Map<String, Object> conn : connections) {
                        String apiKey = (String) conn.get("apiKey");
                        String vaultRef = (String) conn.get("apiKeyVaultRef");
                        if (apiKey != null && !apiKey.isBlank()) {
                            if (vaultRef == null || vaultRef.isBlank() || vaultRef.startsWith("staged-ref-")) {
                                vaultRef = "vault-key-"
                                        + UUID.randomUUID()
                                                .toString()
                                                .replace("-", "")
                                                .substring(0, 16);
                                conn.put("apiKeyVaultRef", vaultRef);
                            }
                            vault.store(vaultRef, apiKey.getBytes(StandardCharsets.UTF_8), ownerId);
                            conn.remove("apiKey");
                            modified = true;
                        } else {
                            if (conn.containsKey("apiKey")) {
                                conn.remove("apiKey");
                                modified = true;
                            }
                        }
                    }
                }
                if (modified) {
                    scrubbed.add(objectMapper.writeValueAsString(payload));
                } else {
                    scrubbed.add(selection);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Onboarding selections must be valid JSON", e);
            }
        }
        return scrubbed;
    }

    /**
     * Persists a draft mutation using optimistic concurrency on its revision. Used by the legacy
     * numbered-step façade that delegates to the durable draft instead of Caffeine state.
     */
    public boolean replaceDraft(OnboardingDraftDto draft) {
        return draftStore.update(draft, draft.revision());
    }

    /**
     * Updates the draft's selected non-secret IDs (provider/model/connection/profile/route references).
     */
    public Optional<OnboardingDraftDto> updateSelections(String draftId, List<String> selections) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty()) return existing;
        OnboardingDraftDto d = existing.get();
        OnboardingDraftDto updated = new OnboardingDraftDto(
                d.draftId(),
                d.ownerId(),
                d.purpose(),
                d.planFingerprint(),
                d.currentStepId(),
                d.completedStepIds(),
                selections,
                d.safeFieldErrors(),
                d.pendingApprovalIds(),
                d.state(),
                d.revision(),
                d.expiresAt(),
                d.createdAt(),
                Instant.now());
        if (draftStore.update(updated, d.revision())) {
            return Optional.of(updated);
        }
        return draftStore.findById(draftId);
    }

    /**
     * Finalizes the draft: marks it COMPLETED. Staged/activated records (connections, profiles, routes)
     * are committed by their own ports in later phases; O1 finalization simply closes the draft.
     */
    public Optional<OnboardingDraftDto> commit(String draftId) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty()) return existing;
        OnboardingDraftDto d = existing.get();

        if (!"review".equals(d.currentStepId())) {
            throw new IllegalStateException("Draft cannot be committed unless in review stage");
        }

        // Activate connection settings on commit
        if (d.selectedNonSecretIds() != null && !d.selectedNonSecretIds().isEmpty()) {
            try {
                String payloadStr = d.selectedNonSecretIds().get(0);
                Map<String, Object> payload = objectMapper.readValue(payloadStr, Map.class);
                List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
                Map<String, Object> concernRoutes = (Map<String, Object>) payload.get("concernRoutes");
                if (connections != null && concernRoutes != null) {
                    List<Integer> agentLoopIndices = (List<Integer>) concernRoutes.get("agentLoop");
                    if (agentLoopIndices != null && !agentLoopIndices.isEmpty()) {
                        int activeIdx = agentLoopIndices.get(0);
                        if (activeIdx >= 0 && activeIdx < connections.size()) {
                            Map<String, Object> activeConn = connections.get(activeIdx);
                            String provider = (String) activeConn.get("provider");
                            String modelId = (String) activeConn.get("modelId");
                            String vaultRef = (String) activeConn.get("apiKeyVaultRef");

                            if (provider != null) {
                                vault.storeSystemKey(
                                        VaultConstants.LLM_PROVIDER_KEY, provider.getBytes(StandardCharsets.UTF_8));
                            }
                            if (modelId != null) {
                                vault.storeSystemKey(
                                        VaultConstants.LLM_MODEL_KEY, modelId.getBytes(StandardCharsets.UTF_8));
                            }
                            if (vaultRef != null) {
                                vault.get(vaultRef, d.ownerId()).ifPresent(vk -> {
                                    try (VaultKey k = vk) {
                                        vault.storeSystemKey(VaultConstants.LLM_API_KEY, k.get());
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to activate LLM configuration on draft commit", e);
            }
        }

        OnboardingDraftDto updated = new OnboardingDraftDto(
                d.draftId(),
                d.ownerId(),
                d.purpose(),
                d.planFingerprint(),
                d.currentStepId(),
                d.completedStepIds(),
                d.selectedNonSecretIds(),
                d.safeFieldErrors(),
                d.pendingApprovalIds(),
                OnboardingDraftState.COMPLETED,
                d.revision(),
                d.expiresAt(),
                d.createdAt(),
                Instant.now());
        if (draftStore.update(updated, d.revision())) {
            return Optional.of(updated);
        }
        return draftStore.findById(draftId);
    }

    public boolean discard(String draftId, String ownerId) {
        Optional<OnboardingDraftDto> draft = draftStore.findById(draftId);
        if (draft.isEmpty() || !draft.get().ownerId().equals(ownerId)) {
            return false;
        }
        return draftStore.delete(draftId) > 0;
    }

    private void confirmBootstrapIfNeeded(String ownerId) {
        Optional<BootstrapStateDto> state = bootstrapStateStore.getState();
        if (state.isPresent() && state.get().isCredentialIssued()) {
            boolean confirmed = bootstrapStateStore.confirmClaim(state.get().revision(), ownerId, Instant.now());
            if (confirmed) {
                log.infof("Bootstrap confirmed via first draft creation for owner=%s", ownerId);
            }
        }
    }
}
