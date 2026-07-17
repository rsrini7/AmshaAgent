package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;
import com.amsha.onboarding.spi.CompensatableOnboardingCommit;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.onboarding.spi.ValidatedOnboardingInput;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles the crash-safe commit saga for onboarding drafts. Extension commits are persisted
 * via {@link OnboardingCommitReceiptStorePort} so that restart compensation is possible.
 * Extracted from {@link OnboardingCoordinator} to keep that class under the PMD
 * ExcessiveClassLength threshold.
 */
@ApplicationScoped
public class OnboardingCommitSaga {

    private static final Logger log = Logger.getLogger(OnboardingCommitSaga.class);

    @Inject
    OnboardingDraftStorePort draftStore;

    @Inject
    OnboardingCommitReceiptStorePort receiptStore;

    @Inject
    SecretVault vault;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Instance<OnboardingExtension> extensions;

    @Inject
    OnboardingSelectionScrubber scrubber;

    /**
     * Commits the draft with crash-safe extension saga support. Receipts are persisted in the
     * dedicated {@link OnboardingCommitReceiptStorePort} so that restart compensation is possible.
     * Each extension commit is recorded as PREPARED before side effects and COMMITTED after, keyed
     * by {@code draftId:extensionId}. On any failure the already-COMMITTED receipts are compensated
     * in reverse order, and the persisted receipts are updated accordingly.
     */
    public Optional<OnboardingDraftDto> commitDraft(String draftId) {
        Optional<OnboardingDraftDto> existing = draftStore.findById(draftId);
        if (existing.isEmpty()) return existing;
        OnboardingDraftDto d = existing.get();

        if (!"review".equals(d.currentStepId())) {
            throw new IllegalStateException("Draft cannot be committed unless in review stage");
        }

        Map<String, OnboardingCommitReceiptDto> alreadyCommitted = loadPriorReceipts(draftId);
        List<ActiveCommit> activeCommits = new ArrayList<>();

        try {
            for (OnboardingExtension ext : extensions) {
                ActiveCommit ac = tryCommitExtension(d, ext, alreadyCommitted);
                if (ac != null) {
                    activeCommits.add(ac);
                }
            }
        } catch (Exception e) {
            log.errorf("Extension commit failed for draft %s; compensating in reverse: %s", draftId, e.getMessage());
            compensateAll(activeCommits, draftId);
            throw new IllegalStateException("Extension commit failed: " + e.getMessage(), e);
        }

        try {
            activateLlmSettings(d);
            OnboardingDraftDto updated = markCompleted(d);
            if (draftStore.update(updated, d.revision())) {
                return Optional.of(updated);
            }
            throw new IllegalStateException("Draft update failed after extension commits");
        } catch (Exception e) {
            log.errorf("Draft completion failed for %s; compensating extensions: %s", draftId, e.getMessage());
            compensateAll(activeCommits, draftId);
            throw new IllegalStateException("Onboarding completion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Activates LLM connection settings from the draft selections. Extracts the active connection
     * from the concernRoutes and stores provider/model/API-key in the system vault.
     */
    @SuppressWarnings("unchecked")
    public void activateLlmSettings(OnboardingDraftDto d) {
        if (d.selectedNonSecretIds() == null || d.selectedNonSecretIds().isEmpty()) return;
        try {
            String payloadStr = d.selectedNonSecretIds().get(0);
            Map<String, Object> payload = objectMapper.readValue(payloadStr, Map.class);
            List<Map<String, Object>> connections = (List<Map<String, Object>>) payload.get("connections");
            Map<String, Object> concernRoutes = (Map<String, Object>) payload.get("concernRoutes");
            if (connections == null || concernRoutes == null) return;
            List<Integer> agentLoopIndices = (List<Integer>) concernRoutes.get("agentLoop");
            if (agentLoopIndices == null || agentLoopIndices.isEmpty()) return;
            int activeIdx = agentLoopIndices.get(0);
            if (activeIdx < 0 || activeIdx >= connections.size()) return;
            Map<String, Object> activeConn = connections.get(activeIdx);
            storeSystemSetting(activeConn, "provider", VaultConstants.LLM_PROVIDER_KEY);
            storeSystemSetting(activeConn, "modelId", VaultConstants.LLM_MODEL_KEY);
            String vaultRef = (String) activeConn.get("apiKeyVaultRef");
            if (vaultRef != null) {
                vault.get(vaultRef, d.ownerId()).ifPresent(vk -> {
                    try (VaultKey k = vk) {
                        vault.storeSystemKey(VaultConstants.LLM_API_KEY, k.get());
                    }
                });
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to activate LLM configuration on draft commit", e);
        }
    }

    // ---- internal saga helpers ----

    private Map<String, OnboardingCommitReceiptDto> loadPriorReceipts(String draftId) {
        Map<String, OnboardingCommitReceiptDto> map = new LinkedHashMap<>();
        if (receiptStore != null) {
            for (OnboardingCommitReceiptDto r : receiptStore.listByDraftId(draftId)) {
                map.put(r.extensionId(), r);
            }
        }
        return map;
    }

    private ActiveCommit tryCommitExtension(
            OnboardingDraftDto d, OnboardingExtension ext, Map<String, OnboardingCommitReceiptDto> alreadyCommitted) {
        String extId = ext.descriptor().extensionId();
        if (!d.completedStepIds().contains(extId)) return null;
        String operationId = d.draftId() + ":" + extId;

        OnboardingCommitReceiptDto prior = alreadyCommitted.get(extId);
        if (prior != null) {
            switch (prior.state()) {
                case "COMMITTED" -> {
                    CompensatableOnboardingCommit reloaded = reloadCommit(d, ext, extId);
                    log.infof(
                            "[ONBOARDING] Extension %s already committed (receipt=%s); resuming",
                            extId, prior.receipt());
                    return new ActiveCommit(extId, operationId, prior.receipt(), reloaded);
                }
                case "PREPARED" ->
                    log.infof("[ONBOARDING] Extension %s has PREPARED receipt; re-executing commit", extId);
                case "COMPENSATED" ->
                    log.infof("[ONBOARDING] Extension %s was compensated; re-executing commit", extId);
                case "COMPENSATION_FAILED" ->
                    throw new IllegalStateException(
                            "Extension " + extId + " requires manual remediation after failed compensation");
                default ->
                    throw new IllegalStateException(
                            "Extension " + extId + " has unknown receipt state: " + prior.state());
            }
        }

        OnboardingContext ctx = new OnboardingContext(d.ownerId(), d.draftId(), extId);
        PreparedOnboardingStep prepared = ext.prepare(ctx);
        String payload = scrubber.resolveVaultReferences(
                d.ownerId(), scrubber.extractExtensionPayload(d.selectedNonSecretIds(), extId));
        OnboardingInput input = new OnboardingInput(extId, payload);
        OnboardingValidationResult result = ext.validate(ctx, prepared, input);
        if (!result.valid()) {
            throw new IllegalStateException("Extension " + extId + " validation failed: " + result.safeFieldErrors());
        }
        ValidatedOnboardingInput validated = new ValidatedOnboardingInput(input, result);
        CompensatableOnboardingCommit commit = ext.plan(ctx, prepared, validated);

        persistReceipt(extId, d.draftId(), operationId, "", "PREPARED");
        String receipt = commit.commit(operationId);
        try {
            persistReceipt(extId, d.draftId(), operationId, receipt, "COMMITTED");
        } catch (Exception receiptFailure) {
            compensateAfterReceiptFailure(commit, operationId, extId, d.draftId(), receipt, receiptFailure);
            throw new IllegalStateException(
                    "Could not persist committed receipt for extension " + extId, receiptFailure);
        }
        log.infof("[ONBOARDING] Committed extension %s receipt=%s for draft %s", extId, receipt, d.draftId());
        return new ActiveCommit(extId, operationId, receipt, commit);
    }

    private CompensatableOnboardingCommit reloadCommit(OnboardingDraftDto d, OnboardingExtension ext, String extId) {
        OnboardingContext ctx = new OnboardingContext(d.ownerId(), d.draftId(), extId);
        PreparedOnboardingStep prepared = ext.prepare(ctx);
        String payload = scrubber.resolveVaultReferences(
                d.ownerId(), scrubber.extractExtensionPayload(d.selectedNonSecretIds(), extId));
        OnboardingInput input = new OnboardingInput(extId, payload);
        OnboardingValidationResult result = ext.validate(ctx, prepared, input);
        return ext.plan(ctx, prepared, new ValidatedOnboardingInput(input, result));
    }

    private void persistReceipt(String extId, String draftId, String operationId, String receipt, String state) {
        if (receiptStore == null) return;
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                UUID.randomUUID().toString(),
                draftId,
                extId,
                operationId,
                receipt == null ? "" : receipt,
                state,
                now,
                now));
    }

    private void compensateAll(List<ActiveCommit> activeCommits, String draftId) {
        for (int i = activeCommits.size() - 1; i >= 0; i--) {
            ActiveCommit ac = activeCommits.get(i);
            try {
                ac.commit().compensate(ac.operationId(), ac.receipt());
                persistReceipt(ac.extensionId(), draftId, ac.operationId(), ac.receipt(), "COMPENSATED");
                log.infof("[ONBOARDING] Compensated extension %s for draft %s", ac.extensionId(), draftId);
            } catch (Exception ce) {
                log.errorf(
                        "Compensation failed for extension %s draft %s: %s",
                        ac.extensionId(), draftId, ce.getMessage());
                persistReceipt(ac.extensionId(), draftId, ac.operationId(), ac.receipt(), "COMPENSATION_FAILED");
            }
        }
    }

    private void compensateAfterReceiptFailure(
            CompensatableOnboardingCommit commit,
            String operationId,
            String extensionId,
            String draftId,
            String receipt,
            Exception receiptFailure) {
        try {
            commit.compensate(operationId, receipt);
            persistReceipt(extensionId, draftId, operationId, receipt, "COMPENSATED");
        } catch (Exception compensationFailure) {
            receiptFailure.addSuppressed(compensationFailure);
            try {
                persistReceipt(extensionId, draftId, operationId, receipt, "COMPENSATION_FAILED");
            } catch (Exception persistenceFailure) {
                receiptFailure.addSuppressed(persistenceFailure);
            }
        }
    }

    private OnboardingDraftDto markCompleted(OnboardingDraftDto d) {
        return new OnboardingDraftDto(
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
    }

    private void storeSystemSetting(Map<String, Object> conn, String sourceKey, String vaultKey) {
        String value = (String) conn.get(sourceKey);
        if (value != null) {
            vault.storeSystemKey(vaultKey, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record ActiveCommit(
            String extensionId, String operationId, String receipt, CompensatableOnboardingCommit commit) {}
}
