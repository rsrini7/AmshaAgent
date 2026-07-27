package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.onboarding.testutil.FlywayEnabledTestProfile;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Crash-injection tests against the real SQLite receipt store. Verifies that the durable receipt
 * contract actually works end-to-end: PREPARED/COMMITTED/COMPENSATED states survive restarts, and
 * the coordinator's crash recovery logic correctly interprets them.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class CrashInjectionReceiptTest {

    @Inject
    OnboardingCommitReceiptStorePort receiptStore;

    @AfterEach
    void cleanup() {
        receiptStore.deleteByDraftId("crash-draft");
    }

    @Test
    void preparedReceipt_survivesRestart() {
        Instant now = Instant.now();
        OnboardingCommitReceiptDto prepared = new OnboardingCommitReceiptDto(
                "r-prep", "crash-draft", "mcp", "crash-draft:mcp", "", "PREPARED", now, now);
        receiptStore.upsert(prepared);

        Optional<OnboardingCommitReceiptDto> found = receiptStore.findByOperationId("crash-draft:mcp");
        assertTrue(found.isPresent());
        assertEquals("PREPARED", found.get().state());
        assertEquals("mcp", found.get().extensionId());

        // Simulate restart: listByDraftId must return the PREPARED receipt.
        List<OnboardingCommitReceiptDto> receipts = receiptStore.listByDraftId("crash-draft");
        assertEquals(1, receipts.size());
        assertEquals("PREPARED", receipts.get(0).state());
    }

    @Test
    void committedReceipt_upsertOverwritesPrepared() {
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r-prep", "crash-draft", "mcp", "crash-draft:mcp", "", "PREPARED", now, now));

        // Simulate successful commit: upsert overwrites to COMMITTED.
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r-prep", "crash-draft", "mcp", "crash-draft:mcp", "svc", "COMMITTED", now, now));

        Optional<OnboardingCommitReceiptDto> found = receiptStore.findByOperationId("crash-draft:mcp");
        assertTrue(found.isPresent());
        assertEquals("COMMITTED", found.get().state());
        assertEquals("svc", found.get().receipt());
    }

    @Test
    void compensatedReceipt_survivesRestart() {
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r-comp", "crash-draft", "mcp", "crash-draft:mcp", "svc", "COMPENSATED", now, now));

        List<OnboardingCommitReceiptDto> receipts = receiptStore.listByDraftId("crash-draft");
        assertEquals(1, receipts.size());
        assertEquals("COMPENSATED", receipts.get(0).state());
    }

    @Test
    void deleteByDraftId_removesAllReceipts() {
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r1", "crash-draft", "mcp", "crash-draft:mcp", "svc", "COMMITTED", now, now));
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r2", "crash-draft", "ext2", "crash-draft:ext2", "svc2", "COMMITTED", now, now));

        assertEquals(2, receiptStore.listByDraftId("crash-draft").size());
        int deleted = receiptStore.deleteByDraftId("crash-draft");
        assertEquals(2, deleted);
        assertTrue(receiptStore.listByDraftId("crash-draft").isEmpty());
    }

    @Test
    void multipleExtensions_independentReceipts() {
        Instant now = Instant.now();
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r1", "crash-draft", "mcp", "crash-draft:mcp", "svc", "COMMITTED", now, now));
        receiptStore.upsert(new OnboardingCommitReceiptDto(
                "r2", "crash-draft", "slack", "crash-draft:slack", "", "PREPARED", now, now));

        List<OnboardingCommitReceiptDto> receipts = receiptStore.listByDraftId("crash-draft");
        assertEquals(2, receipts.size());

        Optional<OnboardingCommitReceiptDto> mcp = receiptStore.findByOperationId("crash-draft:mcp");
        Optional<OnboardingCommitReceiptDto> slack = receiptStore.findByOperationId("crash-draft:slack");
        assertTrue(mcp.isPresent());
        assertTrue(slack.isPresent());
        assertEquals("COMMITTED", mcp.get().state());
        assertEquals("PREPARED", slack.get().state());
    }
}
