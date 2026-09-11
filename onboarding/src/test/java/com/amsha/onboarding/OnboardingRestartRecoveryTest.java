package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.onboarding.spi.CompensatableOnboardingCommit;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.onboarding.spi.ValidatedOnboardingInput;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.security.api.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Process-restart recovery tests for {@link OnboardingCommitSaga}.
 * Verifies recovery behavior across all receipt states: COMMITTED, PREPARED, COMPENSATED,
 * and COMPENSATION_FAILED.
 */
class OnboardingRestartRecoveryTest {

    private OnboardingDraftStorePort draftStore;
    private InMemoryReceiptStore receiptStore;
    private SecretVault vault;
    private OnboardingSelectionScrubber scrubber;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        draftStore = mock(OnboardingDraftStorePort.class);
        receiptStore = new InMemoryReceiptStore();
        vault = mock(SecretVault.class);
        objectMapper = new ObjectMapper();

        scrubber = new OnboardingSelectionScrubber();
        scrubber.vault = vault;
        scrubber.objectMapper = objectMapper;
    }

    private OnboardingDraftDto createDraft(String draftId, List<String> completedSteps) {
        Instant now = Instant.now();
        return new OnboardingDraftDto(
                draftId,
                "owner-1",
                OnboardingDraftPurpose.FIRST_RUN,
                "fingerprint",
                "review",
                completedSteps,
                List.of("{\"mcp\":{}}"),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                now.plus(Duration.ofDays(7)),
                now,
                now);
    }

    private OnboardingExtension mockExtension(String extId, CompensatableOnboardingCommit commit) {
        OnboardingExtension ext = mock(OnboardingExtension.class);
        OnboardingStepDescriptor desc =
                new OnboardingStepDescriptor(extId, "1.0", 1, false, List.of(), "title." + extId, "desc." + extId);
        when(ext.descriptor()).thenReturn(desc);
        PreparedOnboardingStep prep = mock(PreparedOnboardingStep.class);
        when(ext.prepare(any(OnboardingContext.class))).thenReturn(prep);
        when(ext.validate(any(OnboardingContext.class), any(), any(OnboardingInput.class)))
                .thenReturn(OnboardingValidationResult.ok());
        when(ext.plan(any(OnboardingContext.class), any(), any(ValidatedOnboardingInput.class)))
                .thenReturn(commit);
        return ext;
    }

    @SuppressWarnings("unchecked")
    private Instance<OnboardingExtension> instanceOf(List<OnboardingExtension> list) {
        Instance<OnboardingExtension> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(_ -> list.iterator());
        return instance;
    }

    private OnboardingCommitSaga createSaga(List<OnboardingExtension> extensions) {
        OnboardingCommitSaga saga = new OnboardingCommitSaga();
        saga.draftStore = draftStore;
        saga.receiptStore = receiptStore;
        saga.vault = vault;
        saga.objectMapper = objectMapper;
        saga.extensions = instanceOf(extensions);
        saga.scrubber = scrubber;
        saga.vaultPriority = false;
        return saga;
    }

    @Test
    void testPriorCommittedReceipt_ReverseCompensatedOnSubsequentFailure() {
        String draftId = "draft-restart-1";
        OnboardingDraftDto draft = createDraft(draftId, List.of("ext1", "ext2"));
        when(draftStore.findById(draftId)).thenReturn(Optional.of(draft));

        // Prior run committed ext1 with receipt "receipt-ext1" before crashing
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "rec-1", draftId, "ext1", draftId + ":ext1", "receipt-ext1", "COMMITTED", now, now));

        CompensatableOnboardingCommit commitExt1 = mock(CompensatableOnboardingCommit.class);
        OnboardingExtension ext1 = mockExtension("ext1", commitExt1);

        CompensatableOnboardingCommit commitExt2 = mock(CompensatableOnboardingCommit.class);
        when(commitExt2.commit(draftId + ":ext2"))
                .thenThrow(new RuntimeException("Connection refused by provider during ext2 commit"));
        OnboardingExtension ext2 = mockExtension("ext2", commitExt2);

        // Restart process: fresh saga instance
        OnboardingCommitSaga saga = createSaga(List.of(ext1, ext2));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> saga.commitDraft(draftId));
        assertTrue(ex.getMessage().contains("Extension commit failed"));

        // Verify ext1 was NOT re-committed (since it was already COMMITTED)
        verify(commitExt1, never()).commit(any());

        // Verify ext1 was compensated in reverse order after ext2 failed!
        verify(commitExt1).compensate(draftId + ":ext1", "receipt-ext1");

        // Verify receipt store reflects COMPENSATED for ext1
        Optional<OnboardingCommitReceiptDto> ext1Receipt = receiptStore.findByOperationId(draftId + ":ext1");
        assertTrue(ext1Receipt.isPresent());
        assertEquals("COMPENSATED", ext1Receipt.get().state());
    }

    @Test
    void testMultipleExtensions_StrictReverseOrderCompensationOnFailure() {
        String draftId = "draft-restart-reverse";
        OnboardingDraftDto draft = createDraft(draftId, List.of("ext1", "ext2", "ext3"));
        when(draftStore.findById(draftId)).thenReturn(Optional.of(draft));

        CompensatableOnboardingCommit commitExt1 = mock(CompensatableOnboardingCommit.class);
        when(commitExt1.commit(draftId + ":ext1")).thenReturn("receipt-1");
        OnboardingExtension ext1 = mockExtension("ext1", commitExt1);

        CompensatableOnboardingCommit commitExt2 = mock(CompensatableOnboardingCommit.class);
        when(commitExt2.commit(draftId + ":ext2")).thenReturn("receipt-2");
        OnboardingExtension ext2 = mockExtension("ext2", commitExt2);

        CompensatableOnboardingCommit commitExt3 = mock(CompensatableOnboardingCommit.class);
        when(commitExt3.commit(draftId + ":ext3")).thenThrow(new RuntimeException("Failure during ext3 commit"));
        OnboardingExtension ext3 = mockExtension("ext3", commitExt3);

        OnboardingCommitSaga saga = createSaga(List.of(ext1, ext2, ext3));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> saga.commitDraft(draftId));
        assertTrue(ex.getMessage().contains("Extension commit failed"));

        // Verify ext1 and ext2 committed, but ext3 failed
        verify(commitExt1).commit(draftId + ":ext1");
        verify(commitExt2).commit(draftId + ":ext2");

        // Verify strictly reverse order compensation: ext2 compensated BEFORE ext1!
        InOrder inOrder = inOrder(commitExt2, commitExt1);
        inOrder.verify(commitExt2).compensate(draftId + ":ext2", "receipt-2");
        inOrder.verify(commitExt1).compensate(draftId + ":ext1", "receipt-1");

        // Verify receipt states
        assertEquals(
                "COMPENSATED",
                receiptStore.findByOperationId(draftId + ":ext2").get().state());
        assertEquals(
                "COMPENSATED",
                receiptStore.findByOperationId(draftId + ":ext1").get().state());
    }

    @Test
    void testNonLlmReferenceExtensionProvesSpiLifecycle() {
        String draftId = "draft-non-llm-1";
        OnboardingDraftDto draft = createDraft(draftId, List.of("search-integration"));
        when(draftStore.findById(draftId)).thenReturn(Optional.of(draft));
        when(draftStore.update(any(), anyInt())).thenReturn(true);

        CompensatableOnboardingCommit searchCommit = mock(CompensatableOnboardingCommit.class);
        when(searchCommit.commit(draftId + ":search-integration")).thenReturn("search-index-v1");

        OnboardingExtension searchExt = new OnboardingExtension() {
            @Override
            public OnboardingStepDescriptor descriptor() {
                return new OnboardingStepDescriptor(
                        "search-integration", "1.0", 45, false, List.of(), "search.title", "search.desc");
            }

            @Override
            public PreparedOnboardingStep prepare(OnboardingContext context) {
                return new PreparedOnboardingStep("search-integration", "1.0", "fp-search-1", new byte[0]);
            }

            @Override
            public OnboardingValidationResult validate(
                    OnboardingContext context, PreparedOnboardingStep prepared, OnboardingInput input) {
                return OnboardingValidationResult.ok();
            }

            @Override
            public CompensatableOnboardingCommit plan(
                    OnboardingContext context, PreparedOnboardingStep prepared, ValidatedOnboardingInput input) {
                return searchCommit;
            }
        };

        OnboardingCommitSaga saga = createSaga(List.of(searchExt));
        Optional<OnboardingDraftDto> result = saga.commitDraft(draftId);

        assertTrue(result.isPresent());
        verify(searchCommit).commit(draftId + ":search-integration");

        Optional<OnboardingCommitReceiptDto> receipt = receiptStore.findByOperationId(draftId + ":search-integration");
        assertTrue(receipt.isPresent());
        assertEquals("COMMITTED", receipt.get().state());
        assertEquals("search-index-v1", receipt.get().receipt());
    }

    @Test
    void testPriorPreparedReceipt_ReExecutesCommit() {
        String draftId = "draft-restart-2";
        OnboardingDraftDto draft = createDraft(draftId, List.of("ext1"));
        when(draftStore.findById(draftId)).thenReturn(Optional.of(draft));
        when(draftStore.update(any(), anyInt())).thenReturn(true);

        // Prior run crashed after writing PREPARED receipt
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "rec-prep", draftId, "ext1", draftId + ":ext1", "", "PREPARED", now, now));

        CompensatableOnboardingCommit commitExt1 = mock(CompensatableOnboardingCommit.class);
        when(commitExt1.commit(draftId + ":ext1")).thenReturn("receipt-recovered-ext1");
        OnboardingExtension ext1 = mockExtension("ext1", commitExt1);

        // Restart process: fresh saga instance
        OnboardingCommitSaga saga = createSaga(List.of(ext1));

        Optional<OnboardingDraftDto> result = saga.commitDraft(draftId);
        assertTrue(result.isPresent());

        // Verify commit was re-executed
        verify(commitExt1).commit(draftId + ":ext1");

        // Verify receipt transitioned to COMMITTED
        Optional<OnboardingCommitReceiptDto> updatedReceipt = receiptStore.findByOperationId(draftId + ":ext1");
        assertTrue(updatedReceipt.isPresent());
        assertEquals("COMMITTED", updatedReceipt.get().state());
        assertEquals("receipt-recovered-ext1", updatedReceipt.get().receipt());
    }

    @Test
    void testPriorCompensatedReceipt_ReExecutesCommit() {
        String draftId = "draft-restart-3";
        OnboardingDraftDto draft = createDraft(draftId, List.of("ext1"));
        when(draftStore.findById(draftId)).thenReturn(Optional.of(draft));
        when(draftStore.update(any(), anyInt())).thenReturn(true);

        // Prior run failed and was compensated
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "rec-comp", draftId, "ext1", draftId + ":ext1", "old-receipt", "COMPENSATED", now, now));

        CompensatableOnboardingCommit commitExt1 = mock(CompensatableOnboardingCommit.class);
        when(commitExt1.commit(draftId + ":ext1")).thenReturn("receipt-new-ext1");
        OnboardingExtension ext1 = mockExtension("ext1", commitExt1);

        // Restart process / retry: fresh saga instance
        OnboardingCommitSaga saga = createSaga(List.of(ext1));

        Optional<OnboardingDraftDto> result = saga.commitDraft(draftId);
        assertTrue(result.isPresent());

        // Verify commit was re-executed
        verify(commitExt1).commit(draftId + ":ext1");

        // Verify receipt transitioned to COMMITTED
        Optional<OnboardingCommitReceiptDto> updatedReceipt = receiptStore.findByOperationId(draftId + ":ext1");
        assertTrue(updatedReceipt.isPresent());
        assertEquals("COMMITTED", updatedReceipt.get().state());
        assertEquals("receipt-new-ext1", updatedReceipt.get().receipt());
    }

    @Test
    void testPriorCompensationFailedReceipt_BlocksSubsequentCommitsRequiringRemediation() {
        String draftId = "draft-restart-4";
        OnboardingDraftDto draft = createDraft(draftId, List.of("ext1"));
        when(draftStore.findById(draftId)).thenReturn(Optional.of(draft));

        // Prior run had compensation failure, setting COMPENSATION_FAILED
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "rec-fail", draftId, "ext1", draftId + ":ext1", "stuck-receipt", "COMPENSATION_FAILED", now, now));

        CompensatableOnboardingCommit commitExt1 = mock(CompensatableOnboardingCommit.class);
        OnboardingExtension ext1 = mockExtension("ext1", commitExt1);

        // Restart process: fresh saga instance
        OnboardingCommitSaga saga = createSaga(List.of(ext1));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> saga.commitDraft(draftId));
        assertTrue(ex.getMessage().contains("requires manual remediation"));
        assertTrue(ex.getMessage().contains("ext1"));

        // Verify commit was never invoked
        verify(commitExt1, never()).commit(any());
        verify(commitExt1, never()).compensate(any(), any());
    }

    private static class InMemoryReceiptStore implements OnboardingCommitReceiptStorePort {
        private final Map<String, OnboardingCommitReceiptDto> map = new LinkedHashMap<>();

        @Override
        public void upsert(OnboardingCommitReceiptDto receipt) {
            map.put(receipt.operationId(), receipt);
        }

        @Override
        public Optional<OnboardingCommitReceiptDto> findByOperationId(String operationId) {
            return Optional.ofNullable(map.get(operationId));
        }

        @Override
        public List<OnboardingCommitReceiptDto> listByDraftId(String draftId) {
            List<OnboardingCommitReceiptDto> list = new ArrayList<>();
            for (OnboardingCommitReceiptDto r : map.values()) {
                if (draftId.equals(r.draftId())) {
                    list.add(r);
                }
            }
            return list;
        }

        @Override
        public int deleteByDraftId(String draftId) {
            int before = map.size();
            map.values().removeIf(r -> draftId.equals(r.draftId()));
            return before - map.size();
        }
    }
}
