package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsOnboardingDraftStoreAdapterTest {

    private Path tempDir;
    private FsOnboardingDraftStoreAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-draft-test-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();
        adapter = new FsOnboardingDraftStoreAdapter();
        adapter.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            });
        }
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
        assertTrue(adapter.save(d));
        assertFalse(adapter.save(d), "duplicate id must fail");

        Optional<OnboardingDraftDto> found = adapter.findById("d-1");
        assertTrue(found.isPresent());
        assertEquals("owner-1", found.get().ownerId());

        // Pass an UNBUMPED draft (revision still 1, matching expectedRevision). The adapter must
        // bump it to 2 on write, mirroring the SQL backends' optimistic-lock contract.
        OnboardingDraftDto unbumped = new OnboardingDraftDto(
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
                d.revision(),
                d.expiresAt(),
                d.createdAt(),
                Instant.now());
        assertTrue(adapter.update(unbumped, 1));

        OnboardingDraftDto updated = adapter.findById("d-1").orElseThrow();
        assertEquals("step-2", updated.currentStepId());
        assertEquals(2, updated.revision(), "adapter must bump revision on update");

        // Now the persisted revision is 2; a stale update with expectedRevision=1 must fail.
        assertFalse(adapter.update(unbumped, 1), "stale revision must fail");

        assertEquals(1, adapter.delete("d-1"));
        assertTrue(adapter.findById("d-1").isEmpty());
    }

    @Test
    void ownerListingAndActiveFilter() {
        adapter.save(draft("active-1", "owner-a", OnboardingDraftState.DRAFT));
        adapter.save(draft("active-2", "owner-a", OnboardingDraftState.RESUMABLE));
        adapter.save(draft("terminal-1", "owner-a", OnboardingDraftState.COMPLETED));
        adapter.save(draft("other-1", "owner-b", OnboardingDraftState.DRAFT));

        assertEquals(3, adapter.listByOwner("owner-a").size());
        assertEquals(1, adapter.listByOwner("owner-b").size());

        Optional<OnboardingDraftDto> active = adapter.findActiveByOwner("owner-a", Instant.now());
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
        adapter.save(expired);
        assertTrue(adapter.findActiveByOwner("owner-x", Instant.now()).isEmpty());
        assertEquals(
                List.of("expired-1"),
                adapter.findExpired(Instant.now()).stream()
                        .map(OnboardingDraftDto::draftId)
                        .toList());
    }

    @Test
    void deleteReportsAMissAndWrapsAnIoFailure() throws IOException {
        assertEquals(0, adapter.delete("no-such-draft"), "an absent draft deletes nothing");

        // A non-empty directory where the draft file is expected makes deleteIfExists fail.
        Path draftPath = tempDir.resolve("onboarding").resolve("drafts").resolve("d-blocked.json");
        Files.createDirectories(draftPath);
        Files.writeString(draftPath.resolve("child.txt"), "x");

        UncheckedIOException ex = assertThrows(UncheckedIOException.class, () -> adapter.delete("d-blocked"));

        assertTrue(ex.getMessage().startsWith("Failed to delete draft d-blocked"), ex.getMessage());
    }
}
