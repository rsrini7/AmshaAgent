package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingDraftStorePort;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteOnboardingDraftStoreAdapterTest {

    @Inject
    OnboardingDraftStorePort store;

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void cleanDrafts() {
        // The shared integration DB is not wiped between runs, so truncate before each test
        // to keep the absolute-count assertions deterministic.
        jdbi.useHandle(h -> h.execute("DELETE FROM onboarding_draft"));
    }

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
        OnboardingDraftDto d = draft("d-1", "owner-1", OnboardingDraftState.DRAFT);
        assertTrue(store.save(d));
        // Re-saving the same id must fail (create-only contract).
        assertFalse(store.save(d));

        Optional<OnboardingDraftDto> found = store.findById("d-1");
        assertTrue(found.isPresent());
        assertEquals("owner-1", found.get().ownerId());
        assertEquals(OnboardingDraftState.DRAFT, found.get().state());
        assertEquals(List.of("s1"), found.get().completedStepIds());

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
        // Stale revision must fail.
        assertFalse(store.update(bumped, 1));

        OnboardingDraftDto updated = store.findById("d-1").orElseThrow();
        assertEquals("step-2", updated.currentStepId());
        assertEquals(OnboardingDraftState.RESUMABLE, updated.state());
        assertEquals(2, updated.revision());

        assertEquals(1, store.delete("d-1"));
        assertTrue(store.findById("d-1").isEmpty());
    }

    @Test
    void ownerListingAndActiveFilter() {
        Instant now = Instant.now();
        store.save(draft("active-1", "owner-a", OnboardingDraftState.DRAFT));
        store.save(draft("active-2", "owner-a", OnboardingDraftState.RESUMABLE));
        store.save(draft("terminal-1", "owner-a", OnboardingDraftState.COMPLETED));
        store.save(draft("other-1", "owner-b", OnboardingDraftState.DRAFT));

        assertEquals(3, store.listByOwner("owner-a").size());
        assertEquals(1, store.listByOwner("owner-b").size());

        // findActiveByOwner ignores terminal states and returns the most recently updated.
        Optional<OnboardingDraftDto> active = store.findActiveByOwner("owner-a", now);
        assertTrue(active.isPresent());
        assertEquals(OnboardingDraftState.RESUMABLE, active.get().state());
    }

    @Test
    void findActiveByOwnerExcludesExpired() {
        Instant past = Instant.now().minusSeconds(10);
        OnboardingDraftDto expired = new OnboardingDraftDto(
                "expired-1",
                "owner-x",
                OnboardingDraftPurpose.FIRST_RUN,
                "fp",
                "step-1",
                List.of(),
                List.of(),
                null,
                null,
                OnboardingDraftState.DRAFT,
                1,
                past,
                Instant.now().minusSeconds(100),
                Instant.now().minusSeconds(100));
        store.save(expired);
        assertTrue(store.findActiveByOwner("owner-x", Instant.now()).isEmpty());
    }
}
