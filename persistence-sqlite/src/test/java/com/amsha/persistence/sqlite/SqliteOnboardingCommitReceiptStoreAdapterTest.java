package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteOnboardingCommitReceiptStoreAdapterTest {

    @Inject
    OnboardingCommitReceiptStorePort store;

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void cleanReceipts() {
        jdbi.useHandle(h -> h.execute("DELETE FROM onboarding_commit_receipt"));
    }

    private static OnboardingCommitReceiptDto receipt(String id, String draftId, String opId, String state) {
        Instant now = Instant.now();
        return new OnboardingCommitReceiptDto(id, draftId, "ext-" + id, opId, "{\"ok\":true}", state, now, now);
    }

    @Test
    void upsertFindAndIdempotentOverwrite() {
        OnboardingCommitReceiptDto r = receipt("r1", "d-1", "op-1", "COMMITTED");
        store.upsert(r);
        Optional<OnboardingCommitReceiptDto> found = store.findByOperationId("op-1");
        assertTrue(found.isPresent());
        assertEquals("d-1", found.get().draftId());
        assertEquals("COMMITTED", found.get().state());

        // Upsert with the same operationId overwrites prior state (idempotent).
        store.upsert(receipt("r1", "d-1", "op-1", "COMPENSATED"));
        assertEquals(1, store.listByDraftId("d-1").size());
        assertEquals(
                "COMPENSATED", store.findByOperationId("op-1").orElseThrow().state());
    }

    @Test
    void listByDraftAndDelete() {
        store.upsert(receipt("r1", "d-1", "op-1", "PREPARED"));
        store.upsert(receipt("r2", "d-1", "op-2", "COMMITTED"));
        store.upsert(receipt("r3", "d-2", "op-3", "COMMITTED"));

        List<OnboardingCommitReceiptDto> forDraft1 = store.listByDraftId("d-1");
        assertEquals(2, forDraft1.size());
        assertEquals(1, store.listByDraftId("d-2").size());

        assertEquals(2, store.deleteByDraftId("d-1"));
        assertTrue(store.listByDraftId("d-1").isEmpty());
        assertEquals(1, store.listByDraftId("d-2").size());
    }
}
