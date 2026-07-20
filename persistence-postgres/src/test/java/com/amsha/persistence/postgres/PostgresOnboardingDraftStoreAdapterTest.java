package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
@SuppressWarnings("java:S1192")
class PostgresOnboardingDraftStoreAdapterTest {

    @Inject
    OnboardingDraftStorePort store;

    private static OnboardingDraftDto draft(String id, String owner, OnboardingDraftState state) {
        Instant now = Instant.now();
        return new OnboardingDraftDto(
                id,
                owner,
                OnboardingDraftPurpose.FIRST_RUN,
                "fp-" + id,
                "step-1",
                List.of("s1"),
                List.of("ns1"),
                null,
                null,
                state,
                1,
                now.plusSeconds(3600),
                now,
                now);
    }

    @Test
    void saveFindUpdateDeleteRoundTrip() {
        OnboardingDraftDto d = draft("pg-d-1", "owner-1", OnboardingDraftState.DRAFT);
        assertTrue(store.save(d));
        assertFalse(store.save(d), "duplicate id must fail");

        Optional<OnboardingDraftDto> found = store.findById("pg-d-1");
        assertTrue(found.isPresent());
        assertEquals("owner-1", found.get().ownerId());

        OnboardingDraftDto bumped = new OnboardingDraftDto(
                d.draftId(),
                d.ownerId(),
                d.purpose(),
                d.planFingerprint(),
                "step-2",
                List.of("s1", "s2"),
                d.selectedNonSecretIds(),
                d.safeFieldErrors(),
                d.pendingApprovalIds(),
                OnboardingDraftState.RESUMABLE,
                d.revision() + 1,
                d.expiresAt(),
                d.createdAt(),
                Instant.now());
        assertTrue(store.update(bumped, 1));
        assertFalse(store.update(bumped, 1), "stale revision must fail");

        OnboardingDraftDto updated = store.findById("pg-d-1").orElseThrow();
        assertEquals("step-2", updated.currentStepId());
        assertEquals(2, updated.revision());

        assertEquals(1, store.delete("pg-d-1"));
        assertTrue(store.findById("pg-d-1").isEmpty());
    }

    @Test
    void ownerListingAndActiveFilter() {
        store.save(draft("pg-active-1", "owner-a", OnboardingDraftState.DRAFT));
        store.save(draft("pg-active-2", "owner-a", OnboardingDraftState.RESUMABLE));
        store.save(draft("pg-terminal-1", "owner-a", OnboardingDraftState.COMPLETED));
        store.save(draft("pg-other-1", "owner-b", OnboardingDraftState.DRAFT));

        assertEquals(3, store.listByOwner("owner-a").size());
        assertEquals(1, store.listByOwner("owner-b").size());

        Optional<OnboardingDraftDto> active = store.findActiveByOwner("owner-a", Instant.now());
        assertTrue(active.isPresent());
        assertEquals(OnboardingDraftState.RESUMABLE, active.get().state());
    }
}
