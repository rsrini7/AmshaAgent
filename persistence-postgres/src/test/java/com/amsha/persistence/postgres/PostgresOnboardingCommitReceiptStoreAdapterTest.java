package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingCommitReceiptStorePort;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
@SuppressWarnings("java:S1192")
class PostgresOnboardingCommitReceiptStoreAdapterTest {

    @Inject
    OnboardingCommitReceiptStorePort store;

    private static OnboardingCommitReceiptDto receipt(String id, String draftId, String opId, String state) {
        Instant now = Instant.now();
        return new OnboardingCommitReceiptDto(id, draftId, "ext-" + id, opId, "{\"ok\":true}", state, now, now);
    }

    @Test
    void upsertFindAndIdempotentOverwrite() {
        store.upsert(receipt("pg-r1", "d-1", "op-1", "COMMITTED"));
        Optional<OnboardingCommitReceiptDto> found = store.findByOperationId("op-1");
        assertTrue(found.isPresent());
        assertEquals("d-1", found.get().draftId());

        store.upsert(receipt("pg-r1", "d-1", "op-1", "COMPENSATED"));
        assertEquals(1, store.listByDraftId("d-1").size());
        assertEquals(
                "COMPENSATED", store.findByOperationId("op-1").orElseThrow().state());
    }

    @Test
    void listByDraftAndDelete() {
        store.upsert(receipt("pg-r1", "d-1", "op-1", "PREPARED"));
        store.upsert(receipt("pg-r2", "d-1", "op-2", "COMMITTED"));
        store.upsert(receipt("pg-r3", "d-2", "op-3", "COMMITTED"));

        assertEquals(2, store.listByDraftId("d-1").size());
        assertEquals(1, store.listByDraftId("d-2").size());

        assertEquals(2, store.deleteByDraftId("d-1"));
        assertTrue(store.listByDraftId("d-1").isEmpty());
        assertEquals(1, store.listByDraftId("d-2").size());
    }
}
