package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.tools.ToolCategory;
import com.amsha.mcp.transport.McpConnectionLifecycleService;
import com.amsha.onboarding.extension.McpOnboardingExtension;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnboardingCoordinatorExtensionTest {

    @Mock
    OnboardingDraftStorePort draftStore;

    @Mock
    McpConnectionLifecycleService lifecycleService;

    @Mock
    LlmProfileStorePort profileStore;

    OnboardingCoordinator coordinator;
    OnboardingSelectionScrubber scrubber;
    OnboardingCommitSaga commitSaga;
    McpOnboardingExtension mcpExtension;
    InMemoryReceiptStore receiptStore;
    SecretVault vault;
    Instance<OnboardingExtension> exts;

    static final class InMemoryReceiptStore implements OnboardingCommitReceiptStorePort {
        final Map<String, OnboardingCommitReceiptDto> receipts = new LinkedHashMap<>();

        @Override
        public void upsert(OnboardingCommitReceiptDto receipt) {
            receipts.put(receipt.operationId(), receipt);
        }

        @Override
        public Optional<OnboardingCommitReceiptDto> findByOperationId(String operationId) {
            return Optional.ofNullable(receipts.get(operationId));
        }

        @Override
        public List<OnboardingCommitReceiptDto> listByDraftId(String draftId) {
            return receipts.values().stream()
                    .filter(r -> draftId.equals(r.draftId()))
                    .toList();
        }

        @Override
        public int deleteByDraftId(String draftId) {
            int before = receipts.size();
            receipts.values().removeIf(r -> draftId.equals(r.draftId()));
            return before - receipts.size();
        }
    }

    @BeforeEach
    void setUp() {
        mcpExtension = new McpOnboardingExtension();
        mcpExtension.setLifecycleService(lifecycleService);

        exts = mock(Instance.class);
        when(exts.iterator())
                .thenAnswer(inv -> List.<OnboardingExtension>of(mcpExtension).iterator());

        vault = mock(SecretVault.class);
        receiptStore = new InMemoryReceiptStore();
        ObjectMapper objectMapper = new ObjectMapper();

        scrubber = new OnboardingSelectionScrubber();
        scrubber.vault = vault;
        scrubber.objectMapper = objectMapper;

        commitSaga = new OnboardingCommitSaga();
        commitSaga.draftStore = draftStore;
        commitSaga.receiptStore = receiptStore;
        commitSaga.vault = vault;
        commitSaga.objectMapper = objectMapper;
        commitSaga.extensions = exts;
        commitSaga.scrubber = scrubber;
        commitSaga.vaultPriority = true;

        coordinator = new OnboardingCoordinator();
        coordinator.draftStore = draftStore;
        coordinator.bootstrapStateStore = mock(BootstrapStateStorePort.class);
        coordinator.extensions = exts;
        coordinator.scrubber = scrubber;
        coordinator.commitSaga = commitSaga;
        coordinator.profileExporter = new OnboardingProfileExporter();
        coordinator.objectMapper = objectMapper;
    }

    private OnboardingDraftDto draft(String id, OnboardingDraftState state, List<String> completed) {
        Instant now = Instant.now();
        return new OnboardingDraftDto(
                id,
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                completed,
                List.of(),
                null,
                null,
                state,
                1,
                now.plus(Duration.ofDays(7)),
                now,
                now);
    }

    @Test
    void testGetOrCreateFirstRunDraft() {
        when(draftStore.findActiveByOwner(eq("owner-1"), any())).thenReturn(Optional.empty());
        when(draftStore.save(any())).thenReturn(true);

        OnboardingDraftDto d = coordinator.getOrCreateFirstRunDraft("owner-1");
        assertNotNull(d);
        assertEquals("owner-1", d.ownerId());
        assertEquals(OnboardingDraftPurpose.FIRST_RUN, d.purpose());
    }

    @Test
    void testAdvanceStepAndMoveToStep() {
        OnboardingDraftDto d = draft("d-adv", OnboardingDraftState.DRAFT, List.of("welcome"));
        when(draftStore.findById("d-adv")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);

        Optional<OnboardingDraftDto> moved = coordinator.moveToStep("d-adv", "connections");
        assertTrue(moved.isPresent());

        Optional<OnboardingDraftDto> advanced = coordinator.advanceStep("d-adv", "welcome");
        assertTrue(advanced.isPresent());
    }

    @Test
    void testFindActiveAndFindById() {
        OnboardingDraftDto d = draft("d-active", OnboardingDraftState.DRAFT, List.of("welcome"));
        when(draftStore.findActiveByOwner(eq("owner-1"), any())).thenReturn(Optional.of(d));
        when(draftStore.findById("d-active")).thenReturn(Optional.of(d));

        assertTrue(coordinator.findActive("owner-1").isPresent());
        assertTrue(coordinator.findById("d-active").isPresent());
    }

    @Test
    void cleanupExpiredDraftsScrubsAndDeletesWhileContinuingAfterFailure() {
        OnboardingDraftDto cleaned = draft("expired-cleaned", OnboardingDraftState.DRAFT, List.of());
        OnboardingDraftDto failed = draft("expired-failed", OnboardingDraftState.DRAFT, List.of());
        coordinator.scrubber = mock(OnboardingSelectionScrubber.class);
        when(draftStore.findExpired(any())).thenReturn(List.of(cleaned, failed));
        when(draftStore.delete("expired-cleaned")).thenReturn(1);
        doThrow(new IllegalStateException("scrub failed"))
                .when(coordinator.scrubber)
                .cleanupStagedSecrets(failed);

        assertEquals(1, coordinator.cleanupExpiredDrafts());
        verify(coordinator.scrubber).cleanupStagedSecrets(cleaned);
        verify(draftStore).delete("expired-cleaned");
        verify(draftStore, never()).delete("expired-failed");
    }

    @Test
    void activateLlmSettingsPreservesConnectionIndicesWhenEarlierEntryIsMalformed() {
        OnboardingDraftDto base = draft("indexed-route", OnboardingDraftState.DRAFT, List.of());
        String payload = """
                {"connections":["malformed",{"provider":"groq","modelId":"model"}],
                 "concernRoutes":{"agentLoop":[1]}}
                """;
        OnboardingDraftDto selected = new OnboardingDraftDto(
                base.draftId(),
                base.ownerId(),
                base.purpose(),
                base.planFingerprint(),
                base.currentStepId(),
                base.completedStepIds(),
                List.of(payload),
                base.safeFieldErrors(),
                base.pendingApprovalIds(),
                base.state(),
                base.revision(),
                base.expiresAt(),
                base.createdAt(),
                base.updatedAt());

        commitSaga.activateLlmSettings(selected);

        verify(vault, times(2)).storeSystemKey(anyString(), any());
    }

    @Test
    void activateLlmSettingsSkipsVaultWritesWhenVaultPriorityIsDisabled() {
        commitSaga.vaultPriority = false;

        OnboardingDraftDto base = draft("vault-priority-disabled", OnboardingDraftState.DRAFT, List.of());
        OnboardingDraftDto selected = new OnboardingDraftDto(
                base.draftId(),
                base.ownerId(),
                base.purpose(),
                base.planFingerprint(),
                base.currentStepId(),
                base.completedStepIds(),
                List.of("{\"connections\":[]}"),
                base.safeFieldErrors(),
                base.pendingApprovalIds(),
                base.state(),
                base.revision(),
                base.expiresAt(),
                base.createdAt(),
                base.updatedAt());

        commitSaga.activateLlmSettings(selected);

        verify(vault, never()).storeSystemKey(anyString(), any());
    }

    @Test
    void activateLlmSettingsPersistsGenericOpenAiTargetUrl() {
        OnboardingDraftDto base = draft("generic-target-url", OnboardingDraftState.DRAFT, List.of());
        String payload = """
                {"connections":[{"provider":"generic-openai","modelId":"custom-model",
                 "targetUrl":"https://provider.example/v1"}],
                 "concernRoutes":{"agentLoop":[0]}}
                """;
        OnboardingDraftDto selected = new OnboardingDraftDto(
                base.draftId(),
                base.ownerId(),
                base.purpose(),
                base.planFingerprint(),
                base.currentStepId(),
                base.completedStepIds(),
                List.of(payload),
                base.safeFieldErrors(),
                base.pendingApprovalIds(),
                base.state(),
                base.revision(),
                base.expiresAt(),
                base.createdAt(),
                base.updatedAt());

        commitSaga.activateLlmSettings(selected);

        verify(vault)
                .storeSystemKey(
                        eq(VaultConstants.LLM_BASE_URL_KEY),
                        eq("https://provider.example/v1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void commitDraftSkipsEmptyMcpServerList() {
        OnboardingDraftDto base =
                draft("empty-mcp", OnboardingDraftState.DRAFT, List.of("connections", "routes", "mcp"));
        OnboardingDraftDto selected = new OnboardingDraftDto(
                base.draftId(),
                base.ownerId(),
                base.purpose(),
                base.planFingerprint(),
                base.currentStepId(),
                base.completedStepIds(),
                List.of("""
                        {"mcp":{"servers":[]}}
                        """),
                base.safeFieldErrors(),
                base.pendingApprovalIds(),
                base.state(),
                base.revision(),
                base.expiresAt(),
                base.createdAt(),
                base.updatedAt());
        when(draftStore.findById(base.draftId())).thenReturn(Optional.of(selected));
        when(draftStore.update(any(), anyInt())).thenReturn(true);

        Optional<OnboardingDraftDto> committed = coordinator.commitDraft(base.draftId());

        assertTrue(committed.isPresent());
        verify(lifecycleService, never()).persistSuccessfulHttp(any(), any(), any(), any(), any());
        assertTrue(receiptStore.receipts.isEmpty());
    }

    @Test
    void testPrepareAndValidateExtension() {
        var prep = coordinator.prepareExtension("d1", "mcp", "owner-1", "sess-1");
        assertTrue(prep.isPresent());

        var val = coordinator.validateExtension("d1", "mcp", "owner-1", "sess-1", "{}");
        assertTrue(val.isPresent());
    }

    @Test
    void testUpdateConnectionNetworkProfile() {
        String connPayload = "{\"connections\":[{\"name\":\"c1\"}]}";
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-net",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "connections",
                List.of("welcome"),
                List.of(connPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-net")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);

        String netProfileJson = "{\"tlsTrustMode\":\"SYSTEM\"}";
        Optional<OnboardingDraftDto> res = coordinator.updateConnectionNetworkProfile("d-net", 0, netProfileJson);
        assertTrue(res.isPresent());
    }

    // ---- extension plan ----

    @Test
    void effectivePlanIncludesMcpExtension() {
        List<String> plan = coordinator.effectivePlan();
        assertTrue(plan.contains("mcp"), "MCP extension step must appear in effective plan");
        assertTrue(plan.containsAll(List.of("connections", "routes", "review")), "core stages must be preserved");
    }

    // ---- commitDraft extension saga ----

    @Test
    void commitDraft_executesExtensionCommitAndMarksCompleted() {
        OnboardingDraftDto d = draft("d1", OnboardingDraftState.DRAFT, List.of("connections", "routes", "mcp"));
        when(draftStore.findById("d1")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        // Provide extension payload in selections (mcp key in the JSON).
        String mcpPayload =
                "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}}";
        OnboardingDraftDto dWithSel = new OnboardingDraftDto(
                "d1",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d1")).thenReturn(Optional.of(dWithSel));

        Optional<OnboardingDraftDto> result = coordinator.commitDraft("d1");
        assertTrue(result.isPresent());
        assertEquals(OnboardingDraftState.COMPLETED, result.get().state());
        verify(lifecycleService)
                .persistSuccessfulHttp(
                        eq("svc"), eq("https://mcp.example.com"), eq(ToolCategory.NETWORK), eq("owner-1"), any());
    }

    @Test
    void commitDraft_failsWhenExtensionCommitThrows() {
        String mcpPayload =
                "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}}";
        OnboardingDraftDto dWithSel = new OnboardingDraftDto(
                "d2",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d2")).thenReturn(Optional.of(dWithSel));
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThrows(IllegalStateException.class, () -> coordinator.commitDraft("d2"));
        // Commit failed before any side effect persisted, so no compensation delete is needed.
        verify(lifecycleService, never()).deleteByNameAndUserId(any(), any());
        // The draft must NOT be marked COMPLETED. Receipt persistence calls draftStore.update
        // legitimately, so we verify the final COMPLETED state is never written.
        verify(draftStore, never()).update(argThat(d -> d.state() == OnboardingDraftState.COMPLETED), anyInt());
    }

    @Test
    void commitDraft_failsClosedWhenStagedMcpHeaderIsMissing() {
        String mcpPayload = "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\","
                + "\"category\":\"NETWORK\",\"headerVaultRefs\":{\"Authorization\":\"missing-ref\"}}}";
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-missing-header",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-missing-header")).thenReturn(Optional.of(d));
        when(vault.get("missing-ref", "owner-1")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> coordinator.commitDraft("d-missing-header"));
        verify(lifecycleService, never()).persistSuccessfulHttp(any(), any(), any(), any(), any());
    }

    // ---- re-onboarding ----

    @Test
    void getOrCreateReonboardingDraft_createsWithPurpose() {
        when(draftStore.findActiveByOwner(eq("owner-1"), any())).thenReturn(Optional.empty());
        when(draftStore.save(any())).thenReturn(true);
        Instant now = Instant.now();
        when(draftStore.findActiveByOwner(eq("owner-1"), any()))
                .thenReturn(Optional.of(new OnboardingDraftDto(
                        "draft-new",
                        "owner-1",
                        OnboardingDraftPurpose.ADD_CONNECTION,
                        "fingerprint",
                        "connections",
                        List.of(),
                        List.of(),
                        null,
                        null,
                        OnboardingDraftState.DRAFT,
                        1,
                        now.plus(Duration.ofDays(7)),
                        now,
                        now)));

        OnboardingDraftDto result =
                coordinator.getOrCreateReonboardingDraft("owner-1", OnboardingDraftPurpose.ADD_CONNECTION);
        assertEquals(OnboardingDraftPurpose.ADD_CONNECTION, result.purpose());
    }

    // ---- export/import round-trip ----

    @Test
    void exportProfiles_excludesSecrets() {
        Instant now = Instant.now();
        LlmConnectionDto conn = new LlmConnectionDto(
                "c1", "USER", "owner-1", "owner-1", "Conn", "openai", null, null, "API_KEY", null, "ACTIVE", 1, now,
                now);
        when(profileStore.listConnectionsByCreatedBy("owner-1")).thenReturn(List.of(conn));
        when(profileStore.listModelProfilesByConnection("c1"))
                .thenReturn(
                        List.of(new ModelProfileDto("p1", "c1", "gpt-4o", "NATIVE", null, null, true, 1, now, now)));

        Map<String, Object> exported = coordinator.exportProfiles("owner-1", profileStore);
        assertFalse(exported.isEmpty());
        List<Map<String, Object>> conns = uncheckedConnections(exported);
        assertEquals(1, conns.size());
        assertFalse(conns.get(0).containsKey("authScheme"), "export must not include auth scheme details");
        assertFalse(conns.get(0).containsKey("apiKey"), "export must never include secret material");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> uncheckedConnections(Map<String, Object> exported) {
        return (List<Map<String, Object>>) exported.get("connections");
    }

    @Test
    void importProfiles_savesAsStaged() {
        Map<String, Object> data = Map.of(
                "connections",
                        List.of(Map.of("id", "c-import", "providerAdapterId", "openai", "displayName", "Imported")),
                "profiles", List.of(Map.of("id", "p-import", "connectionId", "c-import", "modelId", "gpt-4o")));
        coordinator.importProfiles("owner-1", data, profileStore);
        verify(profileStore).saveConnection(any());
        verify(profileStore).saveModelProfile(any());
    }

    // ---- draft revision/conflict ----

    @Test
    void updateStep_rejectsStaleRevision() {
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-rev",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "welcome",
                List.of(),
                List.of(),
                null,
                null,
                OnboardingDraftState.DRAFT,
                5,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-rev")).thenReturn(Optional.of(d));
        // Simulate optimistic-lock failure (revision mismatch).
        when(draftStore.update(any(), anyInt())).thenReturn(false);
        when(draftStore.findById("d-rev")).thenReturn(Optional.of(d));

        Optional<OnboardingDraftDto> result = coordinator.updateStep("d-rev", "connections", List.of());
        // Should return the existing draft without advancing.
        assertTrue(result.isPresent());
        assertEquals("welcome", result.get().currentStepId());
    }

    @Test
    void commitDraft_rejectsNonReviewStep() {
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-notreview",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "connections",
                List.of(),
                List.of(),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-notreview")).thenReturn(Optional.of(d));

        assertThrows(IllegalStateException.class, () -> coordinator.commitDraft("d-notreview"));
    }

    @Test
    void discard_cleansUpStagedSecrets() {
        // Draft with a staged API key vault reference.
        String payload =
                "{\"connections\":[{\"provider\":\"openai\",\"modelId\":\"gpt-4o\",\"apiKeyVaultRef\":\"vault-key-abc123\"}]}";
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-discard",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "connections",
                List.of(),
                List.of(payload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-discard")).thenReturn(Optional.of(d));
        when(draftStore.delete("d-discard")).thenReturn(1);

        coordinator.discard("d-discard", "owner-1");

        // Staged secret must be cleaned up.
        verify(vault).remove("vault-key-abc123", "owner-1");
        verify(draftStore).delete("d-discard");
    }

    // ---- receipt durability ----

    @Test
    void commitDraft_persistsReceiptsInStore() {
        String mcpPayload =
                "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}}";
        // Override draft with selections.
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-dur",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-dur")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        coordinator.commitDraft("d-dur");

        // Receipt must be persisted in the store with COMMITTED state.
        assertFalse(receiptStore.receipts.isEmpty(), "receipts must be persisted");
        OnboardingCommitReceiptDto receipt =
                receiptStore.receipts.values().iterator().next();
        assertEquals("COMMITTED", receipt.state());
        assertEquals("mcp", receipt.extensionId());
        assertEquals("d-dur", receipt.draftId());
    }

    @Test
    void commitDraft_crashRecovery_skipsAlreadyCommitted() {
        String mcpPayload =
                "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}}";
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-crash",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-crash")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        // Simulate a prior COMMITTED receipt (crash recovery scenario).
        Instant prior = Instant.now().minus(Duration.ofMinutes(5));
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r1", "d-crash", "mcp", "d-crash:mcp", "svc", "COMMITTED", prior, prior));

        coordinator.commitDraft("d-crash");

        // Must NOT re-execute persistSuccessfulHttp (already committed).
        verify(lifecycleService, never()).persistSuccessfulHttp(any(), any(), any(), any(), any());
    }

    @Test
    void commitDraft_crashRecovery_reExecutesAfterPrepared() {
        String mcpPayload =
                "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}}";
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-prep",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-prep")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        // Simulate a prior PREPARED receipt (crash after PREPARED but before COMMITTED).
        Instant prior = Instant.now().minus(Duration.ofMinutes(5));
        receiptStore.upsert(
                new OnboardingCommitReceiptDto("r2", "d-prep", "mcp", "d-prep:mcp", "", "PREPARED", prior, prior));

        coordinator.commitDraft("d-prep");

        // Must re-execute persistSuccessfulHttp (idempotent retry).
        verify(lifecycleService)
                .persistSuccessfulHttp(
                        eq("svc"), eq("https://mcp.example.com"), eq(ToolCategory.NETWORK), eq("owner-1"), any());
        // Receipt must be updated to COMMITTED.
        OnboardingCommitReceiptDto receipt =
                receiptStore.findByOperationId("d-prep:mcp").orElseThrow();
        assertEquals("COMMITTED", receipt.state());
    }

    @Test
    void commitDraft_processRestart_reusesCommittedReceipt() {
        String mcpPayload =
                "{\"mcp\":{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}}";
        OnboardingDraftDto d = new OnboardingDraftDto(
                "d-restart",
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                List.of("connections", "routes", "mcp"),
                List.of(mcpPayload),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                Instant.now().plus(Duration.ofDays(7)),
                Instant.now(),
                Instant.now());
        when(draftStore.findById("d-restart")).thenReturn(Optional.of(d));
        when(draftStore.update(any(), anyInt())).thenReturn(true);
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        // First run (original process) commits and persists a COMMITTED receipt.
        coordinator.commitDraft("d-restart");
        verify(lifecycleService)
                .persistSuccessfulHttp(
                        eq("svc"), eq("https://mcp.example.com"), eq(ToolCategory.NETWORK), eq("owner-1"), any());

        // Simulate a process restart: a brand-new saga instance sharing the same durable stores.
        OnboardingCommitSaga restartedSaga = new OnboardingCommitSaga();
        restartedSaga.draftStore = draftStore;
        restartedSaga.receiptStore = receiptStore;
        restartedSaga.vault = vault;
        restartedSaga.objectMapper = new ObjectMapper();
        restartedSaga.extensions = exts;
        restartedSaga.scrubber = scrubber;

        OnboardingCoordinator restartedCoordinator = new OnboardingCoordinator();
        restartedCoordinator.draftStore = draftStore;
        restartedCoordinator.bootstrapStateStore = mock(BootstrapStateStorePort.class);
        restartedCoordinator.extensions = exts;
        restartedCoordinator.scrubber = scrubber;
        restartedCoordinator.commitSaga = restartedSaga;

        // Re-stub the lifecycle service for the restarted coordinator; the COMMITTED receipt must
        // cause the saga to skip re-execution entirely.
        Mockito.reset(lifecycleService);
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        when(lifecycleService.persistSuccessfulHttp(any(), any(), any(), any(), any()))
                .thenReturn(new McpConnectionLifecycleService.PersistedConnection(null, List.of()));

        restartedCoordinator.commitDraft("d-restart");
        verify(lifecycleService, never()).persistSuccessfulHttp(any(), any(), any(), any(), any());
    }
}
