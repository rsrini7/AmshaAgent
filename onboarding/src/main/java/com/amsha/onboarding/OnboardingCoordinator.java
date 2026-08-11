package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.core.routing.provider.NetworkProfilePolicy;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Coordinator owning the durable onboarding draft lifecycle. It is the single source of truth: the
 * legacy Caffeine-backed wizard state is removed. The first authenticated draft creation confirms
 * the bootstrap CLAIMED transition for the same principal.
 *
 * <p>Heavy operations are delegated to focused beans:
 * <ul>
 *   <li>{@link OnboardingSelectionScrubber} — secret scrubbing, vault resolution, connection validation</li>
 *   <li>{@link OnboardingCommitSaga} — crash-safe extension commit orchestration</li>
 * </ul>
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
    Instance<OnboardingExtension> extensions;

    @Inject
    OnboardingSelectionScrubber scrubber;

    @Inject
    OnboardingCommitSaga commitSaga;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    OnboardingProfileExporter profileExporter;

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
        incrementCounter("amsha.onboarding.draft.created");
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

        int targetIdx = PLAN.indexOf(targetStepId);
        int connectionsIdx = PLAN.indexOf("connections");
        int routesIdx = PLAN.indexOf("routes");
        if (targetIdx > connectionsIdx
                && !d.completedStepIds().contains("connections")
                && !d.currentStepId().equals("connections")) {
            return Optional.empty();
        }
        if (targetIdx > routesIdx
                && !d.completedStepIds().contains("routes")
                && !d.currentStepId().equals("routes")
                && !(targetStepId.equals("review") && d.currentStepId().equals("connections"))) {
            return Optional.empty();
        }

        List<String> completed = new ArrayList<>(d.completedStepIds());
        if (!completed.contains(d.currentStepId()) && !"welcome".equals(d.currentStepId())) {
            completed.add(d.currentStepId());
        }
        // Quick Start intentionally skips the optional routing screen. Persist that skipped
        // stage so the review transition remains consistent with the durable plan.
        if (targetStepId.equals("review") && d.currentStepId().equals("connections") && !completed.contains("routes")) {
            completed.add("routes");
        }

        List<String> processedSelections = scrubber.processAndScrubSecrets(
                d.ownerId(), selections != null ? selections : d.selectedNonSecretIds());

        if ("connections".equals(targetStepId) && processedSelections != null && !processedSelections.isEmpty()) {
            String errors = scrubber.validateConnections(processedSelections);
            if (errors != null) {
                OnboardingDraftDto withErrors = new OnboardingDraftDto(
                        d.draftId(),
                        d.ownerId(),
                        d.purpose(),
                        d.planFingerprint(),
                        d.currentStepId(),
                        d.completedStepIds(),
                        processedSelections,
                        errors,
                        d.pendingApprovalIds(),
                        d.state(),
                        d.revision(),
                        d.expiresAt(),
                        d.createdAt(),
                        Instant.now());
                draftStore.update(withErrors, d.revision());
                return Optional.of(withErrors);
            }
        }

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

    /**
     * Persists a draft mutation using optimistic concurrency on its revision.
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
     * Finalizes the draft: marks it COMPLETED. Activates LLM settings from selections.
     */
    public Optional<OnboardingDraftDto> commit(String draftId) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty()) return existing;
        OnboardingDraftDto d = existing.get();
        if (!"review".equals(d.currentStepId())) {
            throw new IllegalStateException("Draft cannot be committed unless in review stage");
        }
        commitSaga.activateLlmSettings(d);
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
            incrementCounter("amsha.onboarding.draft.committed");
            return Optional.of(updated);
        }
        return draftStore.findById(draftId);
    }

    public boolean discard(String draftId, String ownerId) {
        Optional<OnboardingDraftDto> draft = draftStore.findById(draftId);
        if (draft.isEmpty() || !draft.get().ownerId().equals(ownerId)) {
            return false;
        }
        scrubber.cleanupStagedSecrets(draft.get());
        boolean removed = draftStore.delete(draftId) > 0;
        if (removed) {
            incrementCounter("amsha.onboarding.draft.discarded");
        }
        return removed;
    }

    /**
     * Persists a per-connection {@code networkProfileJson} (TLS trust mode, timeouts, proxy,
     * no-proxy hosts) into the owner's active draft. {@code connectionIndex} selects which
     * connection entry is updated; {@code -1} targets the first connection. Returns the updated
     * draft, or empty when no connections selection or valid index exists.
     */
    public Optional<OnboardingDraftDto> updateConnectionNetworkProfile(
            String draftId, int connectionIndex, String networkProfileJson) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty()
                || networkProfileJson == null
                || networkProfileJson.isBlank()
                || !NetworkProfilePolicy.isValidJson(networkProfileJson)) {
            return existing;
        }
        OnboardingDraftDto d = existing.get();
        List<String> selections = d.selectedNonSecretIds();
        if (selections == null || selections.isEmpty()) {
            return Optional.empty();
        }
        String connPayload = selections.stream()
                .filter(s -> s.contains("\"connections\""))
                .findFirst()
                .orElse(null);
        if (connPayload == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(connPayload);
            JsonNode conns = node.path("connections");
            if (!conns.isArray() || conns.isEmpty()) {
                return Optional.empty();
            }
            int idx = connectionIndex == -1 ? 0 : connectionIndex;
            if (idx < 0) {
                return Optional.empty();
            }
            if (idx >= conns.size()) {
                return Optional.empty();
            }
            ((ObjectNode) conns.get(idx)).put("networkProfileJson", networkProfileJson);
            String updated = objectMapper.writeValueAsString(node);
            List<String> newSelections = new ArrayList<>(selections);
            newSelections.set(selections.indexOf(connPayload), updated);
            Optional<OnboardingDraftDto> result = updateSelections(draftId, newSelections);
            incrementCounter("amsha.onboarding.connection.network-profile.updated");
            return result;
        } catch (Exception e) {
            log.errorf("Failed to update connection network profile for draft %s: %s", draftId, e.getMessage());
            return Optional.empty();
        }
    }

    private void incrementCounter(String name) {
        if (meterRegistry != null) {
            meterRegistry.counter(name).increment();
        }
    }

    public int cleanupExpiredDrafts() {
        Instant now = Instant.now();
        List<OnboardingDraftDto> expired = draftStore.findExpired(now);
        if (expired.isEmpty()) {
            return 0;
        }
        int cleaned = 0;
        for (OnboardingDraftDto d : expired) {
            try {
                scrubber.cleanupStagedSecrets(d);
                int deleted = draftStore.delete(d.draftId());
                if (deleted > 0) {
                    cleaned++;
                    incrementCounter("amsha.onboarding.draft.expired.cleaned");
                    log.infof("[ONBOARDING] Cleaned expired draft %s (owner=%s)", d.draftId(), d.ownerId());
                }
            } catch (Exception e) {
                log.warnf("[ONBOARDING] Failed to clean expired draft %s: %s", d.draftId(), LogUtil.rootMessage(e));
            }
        }
        return cleaned;
    }

    // ---- extension discovery and saga lifecycle ----

    public List<String> effectivePlan() {
        List<String> merged = new ArrayList<>(PLAN);
        for (OnboardingExtension ext : extensions) {
            OnboardingStepDescriptor d = ext.descriptor();
            if (d.optional() && !merged.contains(d.extensionId())) {
                int insertIdx = Math.min(d.order() / 10, merged.size() - 1);
                if (insertIdx < 0) insertIdx = 0;
                merged.add(insertIdx, d.extensionId());
            }
        }
        return List.copyOf(merged);
    }

    private Optional<OnboardingExtension> extensionForStep(String stepId) {
        for (OnboardingExtension ext : extensions) {
            if (ext.descriptor().extensionId().equals(stepId)) {
                return Optional.of(ext);
            }
        }
        return Optional.empty();
    }

    public Optional<PreparedOnboardingStep> prepareExtension(
            String draftId, String stepId, String ownerId, String sessionId) {
        return extensionForStep(stepId).map(ext -> ext.prepare(new OnboardingContext(ownerId, sessionId, stepId)));
    }

    public Optional<OnboardingValidationResult> validateExtension(
            String draftId, String stepId, String ownerId, String sessionId, String jsonPayload) {
        return extensionForStep(stepId).map(ext -> {
            OnboardingContext ctx = new OnboardingContext(ownerId, sessionId, stepId);
            PreparedOnboardingStep prepared = ext.prepare(ctx);
            return ext.validate(ctx, prepared, new OnboardingInput(stepId, jsonPayload));
        });
    }

    /** Delegates crash-safe commit to the saga. */
    public Optional<OnboardingDraftDto> commitDraft(String draftId) {
        Optional<OnboardingDraftDto> result = commitSaga.commitDraft(draftId);
        result.ifPresent(d -> {
            if (d.state() == OnboardingDraftState.COMPLETED) {
                incrementCounter("amsha.onboarding.draft.committed");
            }
        });
        return result;
    }

    /** Extracts the sub-payload for a specific extension from the combined selections list. */
    public String extractExtensionPayload(List<String> selections, String extensionId) {
        return scrubber.extractExtensionPayload(selections, extensionId);
    }

    // ---- O5: re-onboarding, export/import ----

    public OnboardingDraftDto getOrCreateReonboardingDraft(String ownerId, OnboardingDraftPurpose purpose) {
        Optional<OnboardingDraftDto> existing = draftStore.findActiveByOwner(ownerId, Instant.now());
        if (existing.isPresent() && existing.get().purpose() == purpose) {
            return existing.get();
        }
        Instant now = Instant.now();
        String planFingerprint = String.join("->", effectivePlan());
        OnboardingDraftDto draft = new OnboardingDraftDto(
                "draft_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                ownerId,
                purpose,
                planFingerprint,
                "connections",
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

    public Map<String, Object> exportProfiles(String ownerId, LlmProfileStorePort profileStore) {
        return profileExporter.exportProfiles(ownerId, profileStore);
    }

    public void importProfiles(String ownerId, Map<String, Object> data, LlmProfileStorePort profileStore) {
        profileExporter.importProfiles(ownerId, data, profileStore);
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
