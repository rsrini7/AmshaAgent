package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.onboarding.spi.CompensatableOnboardingCommit;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.onboarding.spi.ValidatedOnboardingInput;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    @Inject
    Instance<LlmProfileStorePort> profileStoreInstance;

    @Inject
    OnboardingProfileExporter profileExporter;

    LlmProfileStorePort profileStore;

    @ConfigProperty(name = LlmConfigKeys.VAULT_PRIORITY, defaultValue = "false")
    boolean vaultPriority;

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
            log.errorf(
                    "Extension commit failed for draft %s; compensating in reverse: %s",
                    draftId, LogUtil.rootMessage(e));
            compensateAll(activeCommits, draftId);
            throw new IllegalStateException("Extension commit failed: " + LogUtil.rootMessage(e), e);
        }

        try {
            activateLlmSettings(d);
            activateProfileStore(d);
            OnboardingDraftDto updated = markCompleted(d);
            if (draftStore.update(updated, d.revision())) {
                return Optional.of(updated);
            }
            throw new IllegalStateException("Draft update failed after extension commits");
        } catch (Exception e) {
            log.errorf("Draft completion failed for %s; compensating extensions: %s", draftId, LogUtil.rootMessage(e));
            compensateAll(activeCommits, draftId);
            throw new IllegalStateException("Onboarding completion failed: " + LogUtil.rootMessage(e), e);
        }
    }

    /**
     * Activates LLM connection settings from the draft selections. Extracts the active connection
     * from the concernRoutes and stores provider/model/API-key in the system vault.
     */
    @SuppressWarnings("unchecked")
    public void activateLlmSettings(OnboardingDraftDto d) {
        if (!vaultPriority) return;
        if (d.selectedNonSecretIds() == null || d.selectedNonSecretIds().isEmpty()) return;
        try {
            String payloadStr = d.selectedNonSecretIds().get(0);
            Map<String, Object> payload = objectMapper.readValue(payloadStr, Map.class);
            Object connectionsRaw = payload.get("connections");
            Object concernRoutesRaw = payload.get("concernRoutes");
            if (!(connectionsRaw instanceof List<?> connectionsList)
                    || !(concernRoutesRaw instanceof Map<?, ?> concernRoutes)) {
                return;
            }
            Map<String, Object> concernRoutesMap = (Map<String, Object>) concernRoutes;
            Object agentLoopRaw = concernRoutesMap.get("agentLoop");
            if (!(agentLoopRaw instanceof List<?> agentLoopList) || agentLoopList.isEmpty()) return;
            Object firstIdx = agentLoopList.get(0);
            if (!(firstIdx instanceof Number number)) return;
            int activeIdx = number.intValue();
            if (activeIdx < 0 || activeIdx >= connectionsList.size()) return;
            Object activeConnectionRaw = connectionsList.get(activeIdx);
            if (!(activeConnectionRaw instanceof Map<?, ?> activeConnection)) return;
            Map<String, Object> activeConn = (Map<String, Object>) activeConnection;
            storeSystemSetting(activeConn, "provider", VaultConstants.LLM_PROVIDER_KEY);
            storeSystemSetting(activeConn, "modelId", VaultConstants.LLM_MODEL_KEY);
            storeOptionalSystemSetting(
                    activeConn, VaultConstants.LLM_BASE_URL_KEY, "customBaseUrl", "baseUrl", "targetUrl");
            // Invariant: User credentials remain strictly in USER:<userId> or WORKSPACE:<workspaceId> scope.
            // Do not promote apiKeyVaultRef to system vault keys.

            activateConcernRoutes(concernRoutesMap, connectionsList);
            activateOptionalStages(d.ownerId(), payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to activate LLM configuration on draft commit", e);
        }
    }

    private void activateConcernRoutes(Map<String, Object> routes, List<?> connections) {
        storeConcernRoute(routes, connections, "compaction");
        storeConcernRoute(routes, connections, "classifier");
        storeConcernRoute(routes, connections, "embedding");
    }

    @SuppressWarnings("unchecked")
    private void storeConcernRoute(Map<String, Object> routes, List<?> connections, String concern) {
        Object raw = routes.get(concern);
        if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number num) {
            int idx = num.intValue();
            if (idx >= 0 && idx < connections.size() && connections.get(idx) instanceof Map<?, ?> connMap) {
                Map<String, Object> conn = (Map<String, Object>) connMap;
                String provider = (String) conn.get("provider");
                String model = (String) conn.get("modelId");
                if (provider != null && model != null) {
                    String value = provider + ":" + model;
                    vault.storeSystemKey("amsha.llm." + concern + ".target", value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private void activateOptionalStages(String ownerId, Map<String, Object> payload) {
        storeOptionalSection(ownerId, payload, "integrations", "settings:integrations");
        storeOptionalSection(ownerId, payload, "memory", "settings:memory");
        storeOptionalSection(ownerId, payload, "channels", "settings:channels");
        storeOptionalSection(ownerId, payload, "security", "settings:security");
        storeOptionalSection(ownerId, payload, "advanced", "settings:advanced");
    }

    @SuppressWarnings("unchecked")
    private void storeOptionalSection(String ownerId, Map<String, Object> payload, String sectionKey, String vaultKey) {
        Object section = payload.get(sectionKey);
        if (section instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            try {
                Map<String, Object> scrubbedMap = scrubOptionalMap(ownerId, (Map<String, Object>) rawMap);
                String json = objectMapper.writeValueAsString(scrubbedMap);
                vault.storeSystemKey(vaultKey, json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception _) {
                // Ignore serialization error on optional section
            }
        }
    }

    private Map<String, Object> scrubOptionalMap(String ownerId, Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof String str && isSecretKey(key) && !str.isBlank() && !str.startsWith("vault-key-")) {
                String vaultRef = "vault-key-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                vault.store(vaultRef, str.getBytes(StandardCharsets.UTF_8), ownerId);
                String refKey = key.endsWith("VaultRef") ? key : key + "VaultRef";
                copy.put(refKey, vaultRef);
            } else {
                copy.put(key, val);
            }
        }
        return copy;
    }

    private static boolean isSecretKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return (lower.contains("secret")
                        || lower.contains("token")
                        || lower.contains("password")
                        || lower.contains("key")
                        || lower.contains("pass"))
                && !lower.endsWith("vaultref")
                && !lower.endsWith("ref");
    }

    @SuppressWarnings("unchecked")
    public void activateProfileStore(OnboardingDraftDto d) {
        LlmProfileStorePort store = getProfileStore();
        if (store == null || profileExporter == null) {
            return;
        }
        if (d.selectedNonSecretIds() == null || d.selectedNonSecretIds().isEmpty()) {
            return;
        }
        try {
            String payloadStr = d.selectedNonSecretIds().get(0);
            Map<String, Object> payload = objectMapper.readValue(payloadStr, Map.class);
            Object connsRaw = payload.get("connections");
            if (connsRaw instanceof List<?> connsList && !connsList.isEmpty()) {
                boolean hasProfilesOrRoutes = payload.containsKey("profiles") || payload.containsKey("routes");
                boolean hasIdentifiedConnections = connsList.get(0) instanceof Map<?, ?> m && m.containsKey("id");
                if (hasProfilesOrRoutes || hasIdentifiedConnections) {
                    profileExporter.importProfiles(d.ownerId(), payload, store, true);
                }
            }
        } catch (Exception e) {
            log.warnf(
                    "[ONBOARDING] Failed to activate profile store topology on draft commit: %s",
                    LogUtil.rootMessage(e));
        }
    }

    private LlmProfileStorePort getProfileStore() {
        if (profileStore != null) {
            return profileStore;
        }
        if (profileStoreInstance != null && profileStoreInstance.isResolvable()) {
            return profileStoreInstance.get();
        }
        return null;
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
        if (ext.descriptor().optional()
                && isEmptyOptionalExtensionPayload(scrubber.extractExtensionPayload(d.selectedNonSecretIds(), extId))) {
            return null;
        }
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
                        ac.extensionId(), draftId, LogUtil.rootMessage(ce));
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

    @SuppressWarnings("unchecked")
    private boolean isEmptyOptionalExtensionPayload(String payload) {
        if (payload == null || payload.isBlank() || "{}".equals(payload)) return true;
        try {
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            Object servers = parsed.get("servers");
            return parsed.size() == 1 && servers instanceof List<?> serverList && serverList.isEmpty();
        } catch (Exception _) {
            return false;
        }
    }

    private void storeOptionalSystemSetting(Map<String, Object> conn, String vaultKey, String... sourceKeys) {
        String value = null;
        for (String sourceKey : sourceKeys) {
            Object rawValue = conn.get(sourceKey);
            if (rawValue instanceof String candidate && !candidate.isBlank()) {
                value = candidate;
                break;
            }
        }
        if (value == null) {
            vault.removeSystemKey(vaultKey);
            return;
        }
        vault.storeSystemKey(vaultKey, value.getBytes(StandardCharsets.UTF_8));
    }

    private record ActiveCommit(
            String extensionId, String operationId, String receipt, CompensatableOnboardingCommit commit) {}
}
