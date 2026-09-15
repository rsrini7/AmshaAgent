package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link FsDirectiveStore} against a real {@link FilesystemFileStore} in a temp
 * directory. {@code FsDirectiveStoreTest} mocks the file store, so it only proves delegation — the
 * dedup predicate, the limit arms, the update loop and the purge loop never execute there. This class
 * covers those against real JSON, following the {@code *DeepTest} convention used elsewhere in this
 * package.
 */
@SuppressWarnings({"java:S1192", "java:S5443"}) // Test-only: temp dirs are acceptable here
class FsDirectiveStoreDeepTest {

    private Path tempDir;
    private FsDirectiveStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("amsha-fs-directives-");
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

        store = new FsDirectiveStore();
        store.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    /* best-effort cleanup */
                }
            });
        }
    }

    // ─── insert (dedup) ────────────────────────────────────────

    @Test
    void insertPersistsANewDirective() {
        assertEquals(1, store.insert(active("d1", "user-1", "text one", 0.5)));
        assertEquals(1, store.getAll("user-1", 0).size());
    }

    @Test
    void insertDedupsOnTheSameId() {
        SynthesizedDirective first = active("d1", "user-1", "text one", 0.5);
        assertEquals(1, store.insert(first));
        assertEquals(0, store.insert(first), "the same id is a duplicate");
        assertEquals(1, store.getAll("user-1", 0).size());
    }

    @Test
    void insertDedupsOnTheSameUserAndTextEvenWithADifferentId() {
        assertEquals(1, store.insert(active("d1", "user-1", "identical text", 0.5)));
        assertEquals(
                0,
                store.insert(active("d2", "user-1", "identical text", 0.7)),
                "same user + same text is a duplicate even under a new id");
        assertEquals(1, store.getAll("user-1", 0).size());
    }

    @Test
    void insertKeepsDirectivesWithTheSameTextForDifferentUsers() {
        assertEquals(1, store.insert(active("d1", "user-1", "shared text", 0.5)));
        assertEquals(1, store.insert(active("d2", "user-2", "shared text", 0.5)));
        assertEquals(1, store.getAll("user-2", 0).size());
    }

    // ─── update ────────────────────────────────────────────────

    @Test
    void updateReplacesTheMatchingDirectiveInPlace() {
        store.insert(active("d1", "user-1", "original", 0.5));
        store.insert(active("d2", "user-1", "other", 0.6));

        store.update(active("d1", "user-1", "revised", 0.95));

        List<SynthesizedDirective> all = store.getAll("user-1", 0);
        assertEquals(2, all.size(), "update replaces rather than appends");
        SynthesizedDirective updated =
                all.stream().filter(d -> d.id().equals("d1")).findFirst().orElseThrow();
        assertEquals("revised", updated.text());
        assertEquals(0.95, updated.confidence(), 1e-9);
    }

    @Test
    void updateIgnoresAnUnknownDirective() {
        store.insert(active("d1", "user-1", "original", 0.5));
        assertDoesNotThrow(() -> store.update(active("missing", "user-1", "revised", 0.9)));
        assertEquals(1, store.getAll("user-1", 0).size());
    }

    // ─── getInjectable ─────────────────────────────────────────

    @Test
    void getInjectableKeepsActiveAndShadowOnlyAndSortsByConfidenceDescending() {
        store.insert(active("a", "user-1", "active low", 0.50));
        store.insert(directive("s", "user-1", "shadow high", DirectiveState.SHADOW, 0.99, Instant.now()));
        store.insert(directive("c", "user-1", "candidate", DirectiveState.CANDIDATE, 1.0, Instant.now()));
        store.insert(directive("x", "user-1", "disabled", DirectiveState.DISABLED, 1.0, Instant.now()));

        List<SynthesizedDirective> injectable = store.getInjectable("user-1", 10);
        assertEquals(
                List.of("s", "a"),
                injectable.stream().map(SynthesizedDirective::id).toList(),
                "CANDIDATE and DISABLED are excluded; SHADOW 0.99 sorts above ACTIVE 0.50");
    }

    @Test
    void getInjectableHonoursTheLimit() {
        store.insert(active("a", "user-1", "one", 0.50));
        store.insert(active("b", "user-1", "two", 0.60));
        assertEquals(1, store.getInjectable("user-1", 1).size());
        assertEquals("b", store.getInjectable("user-1", 1).getFirst().id());
    }

    // ─── getAll ────────────────────────────────────────────────

    @Test
    void getAllSortsByCreatedAtDescendingAndAppliesTheLimit() {
        Instant base = Instant.now();
        store.insert(directive("old", "user-1", "oldest", DirectiveState.ACTIVE, 0.5, base.minus(2, ChronoUnit.HOURS)));
        store.insert(directive("mid", "user-1", "middle", DirectiveState.ACTIVE, 0.5, base.minus(1, ChronoUnit.HOURS)));
        store.insert(directive("new", "user-1", "newest", DirectiveState.ACTIVE, 0.5, base));

        assertEquals(
                List.of("new", "mid", "old"),
                store.getAll("user-1", 0).stream().map(SynthesizedDirective::id).toList());
        assertEquals(3, store.getAll("user-1", 10).size(), "a limit above the size is not exceeded");
        assertEquals(1, store.getAll("user-1", 1).size());
        assertTrue(store.getAll("nobody", 0).isEmpty());
    }

    // ─── getById ───────────────────────────────────────────────

    @Test
    void getByIdScansEveryUserFileAndReturnsNullWhenAbsent() {
        store.insert(active("u1-d", "user-1", "one", 0.5));
        store.insert(active("u2-d", "user-2", "two", 0.5));

        assertNotNull(store.getById("u2-d"), "found in the second user file");
        assertEquals("u2-d", store.getById("u2-d").id());
        assertNull(store.getById("does-not-exist"));
    }

    // ─── getByState ────────────────────────────────────────────

    @Test
    void getByStateFiltersSortsByConfidenceAndAppliesTheLimit() {
        store.insert(active("a", "user-1", "low", 0.40));
        store.insert(active("b", "user-1", "high", 0.90));
        store.insert(directive("c", "user-1", "cand", DirectiveState.CANDIDATE, 1.0, Instant.now()));

        List<SynthesizedDirective> activeOnly = store.getByState(DirectiveState.ACTIVE, 0);
        assertEquals(
                List.of("b", "a"),
                activeOnly.stream().map(SynthesizedDirective::id).toList());
        assertEquals(1, store.getByState(DirectiveState.ACTIVE, 1).size());
        assertEquals(1, store.getByState(DirectiveState.CANDIDATE, 10).size());
        assertTrue(store.getByState(DirectiveState.DISABLED, 0).isEmpty());
    }

    // ─── delete ────────────────────────────────────────────────

    @Test
    void deleteRemovesTheDirectiveAndReportsWhetherAnythingMatched() {
        store.insert(active("d1", "user-1", "one", 0.5));
        store.insert(active("d2", "user-1", "two", 0.5));

        assertTrue(store.delete("d1"));
        assertEquals(1, store.getAll("user-1", 0).size());
        assertFalse(store.delete("never-existed"), "nothing matched in any file");
    }

    // ─── purgeExpired ──────────────────────────────────────────

    @Test
    void purgeExpiredRemovesOnlyDisabledDirectivesPastTheCutoff() {
        Instant now = Instant.now();
        store.insert(directive(
                "stale-disabled", "user-1", "stale", DirectiveState.DISABLED, 0.5, now.minus(10, ChronoUnit.DAYS)));
        store.insert(directive("fresh-disabled", "user-1", "fresh", DirectiveState.DISABLED, 0.5, now));
        store.insert(directive(
                "old-but-active", "user-1", "active", DirectiveState.ACTIVE, 0.5, now.minus(10, ChronoUnit.DAYS)));

        assertEquals(1, store.purgeExpired(1), "only the old DISABLED directive is purged");

        List<String> remaining =
                store.getAll("user-1", 0).stream().map(SynthesizedDirective::id).toList();
        assertTrue(remaining.contains("fresh-disabled"));
        assertTrue(remaining.contains("old-but-active"), "state must be DISABLED to be purged");
        assertFalse(remaining.contains("stale-disabled"));

        assertEquals(0, store.purgeExpired(1), "second pass finds nothing left");
    }

    @Test
    void purgeExpiredOverAnEmptyStoreIsZero() {
        assertEquals(0, store.purgeExpired(7));
    }

    // ─── Helpers ───────────────────────────────────────────────

    private static SynthesizedDirective active(String id, String userId, String text, double confidence) {
        return directive(id, userId, text, DirectiveState.ACTIVE, confidence, Instant.now());
    }

    private static SynthesizedDirective directive(
            String id, String userId, String text, DirectiveState state, double confidence, Instant at) {
        return new SynthesizedDirective(id, userId, text, state, confidence, "[]", "hash", 0, 0, at, at);
    }
}
