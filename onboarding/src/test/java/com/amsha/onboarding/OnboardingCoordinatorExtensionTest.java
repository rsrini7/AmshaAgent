package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.security.SecretVault;
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

        @SuppressWarnings("unchecked")
        Instance<OnboardingExtension> exts = mock(Instance.class);
        when(exts.iterator())
                .thenReturn(List.<OnboardingExtension>of(mcpExtension).iterator());

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

        coordinator = new OnboardingCoordinator();
        coordinator.draftStore = draftStore;
        coordinator.bootstrapStateStore = mock(BootstrapStateStorePort.class);
        coordinator.extensions = exts;
        coordinator.scrubber = scrubber;
        coordinator.commitSaga = commitSaga;
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
        List<Map<String, Object>> conns = (List<Map<String, Object>>) exported.get("connections");
        assertEquals(1, conns.size());
        assertFalse(conns.get(0).containsKey("authScheme"), "export must not include auth scheme details");
        assertFalse(conns.get(0).containsKey("apiKey"), "export must never include secret material");
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
}
