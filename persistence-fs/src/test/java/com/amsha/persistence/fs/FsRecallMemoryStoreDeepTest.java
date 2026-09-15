package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-filesystem companion to {@link FsRecallMemoryStoreTest}.
 *
 * <p>{@code FsRecallMemoryStoreTest} drives the adapter through a mocked {@link FilesystemFileStore},
 * so every predicate over a <em>populated</em> list, every {@code limit} arm and the whole
 * {@code purgeExpiredAll} sweep stay unexecuted — a stubbed store hands back the same empty list for
 * every call, which short-circuits the streams before their lambdas ever run. This class binds the
 * adapter to a real store rooted in a temp directory, which is the only way those arms are genuinely
 * reached. The spec deliberately mirrors {@code FsRecallMemoryStoreTest} (see the repo note on
 * cross-backend contract suites: there is no test-jar infrastructure, so the spec is mirrored per
 * test class by convention rather than shared).
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsRecallMemoryStoreDeepTest {

    private Path tempDir;
    private FsRecallMemoryStore recallStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-recall-deep-");
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

        recallStore = new FsRecallMemoryStore();
        recallStore.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void insertOrIgnoreDedupesOnTheWholeCompositeKey() {
        Instant now = Instant.now();
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "first", now));

        recallStore.insertOrIgnore(message("m2", "s1", "u1", 1, "h1", "USER_MESSAGE", "dup", now));
        assertEquals(1, allInSession("s1").size(), "same turn + same hash is a duplicate");

        recallStore.insertOrIgnore(message("m3", "s1", "u1", 2, "h1", "USER_MESSAGE", "new turn", now));
        assertEquals(2, allInSession("s1").size(), "a different turn number is a distinct message");

        recallStore.insertOrIgnore(message("m4", "s1", "u1", 1, "h2", "USER_MESSAGE", "new hash", now));
        assertEquals(3, allInSession("s1").size(), "a different content hash is a distinct message");
    }

    @Test
    void insertBatchIgnoresNullOrEmptyInput() {
        recallStore.insertBatch(null);
        recallStore.insertBatch(List.of());

        assertTrue(allInSession("s1").isEmpty());
        assertTrue(recallStore.search(crossSession(null)).isEmpty(), "nothing was persisted");
    }

    @Test
    void insertBatchMergesWithWhatIsAlreadyStored() {
        Instant now = Instant.now();
        recallStore.insertBatch(List.of(
                message("a", "s1", "u1", 1, "ha", "USER_MESSAGE", "a", now),
                message("b", "s2", "u1", 1, "hb", "USER_MESSAGE", "b", now)));
        assertEquals(1, allInSession("s1").size());
        assertEquals(1, allInSession("s2").size());

        recallStore.insertBatch(List.of(
                message("a-again", "s1", "u1", 1, "ha", "USER_MESSAGE", "a again", now),
                message("c", "s1", "u1", 2, "hc", "USER_MESSAGE", "c", now)));

        List<String> s1 = ids(allInSession("s1"));
        assertEquals(2, s1.size(), "the duplicate hash was skipped and the new one appended");
        assertTrue(s1.contains("c"));
        assertFalse(s1.contains("a-again"));
    }

    @Test
    void searchAcrossSessionsWalksEverySessionFile() {
        Instant now = Instant.now();
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "alpha", now));
        recallStore.insertOrIgnore(message("m2", "s2", "u1", 1, "h2", "USER_MESSAGE", "alpha", now));

        assertEquals(2, recallStore.search(crossSession("alpha")).size(), "a null sessionId searches every file");
        assertTrue(recallStore.search(crossSession("nothing-matches")).isEmpty());
    }

    @Test
    void searchReturnsTheHighestTurnsFirstAndHonoursTheLimit() {
        Instant now = Instant.now();
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "x", now));
        recallStore.insertOrIgnore(message("m2", "s1", "u1", 2, "h2", "USER_MESSAGE", "x", now));
        recallStore.insertOrIgnore(message("m3", "s1", "u1", 3, "h3", "USER_MESSAGE", "x", now));

        List<RecallMessageDto> capped = recallStore.search(new RecallSearchQuery(null, null, "s1", 2, true, null));

        assertEquals(2, capped.size(), "the limit truncates the result");
        assertEquals(
                List.of(3, 2), capped.stream().map(RecallMessageDto::turnNumber).toList());
    }

    @Test
    void getByIdScansEverySessionAndReportsMisses() {
        Instant now = Instant.now();
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "a", now));
        recallStore.insertOrIgnore(message("m2", "s2", "u1", 1, "h2", "USER_MESSAGE", "b", now));

        assertEquals("m2", recallStore.getById("m2").orElseThrow().messageId(), "found in the second file");
        assertTrue(recallStore.getById("no-such-id").isEmpty());
    }

    @Test
    void purgeExpiredDeletesTheFileWhenNothingSurvives() {
        Instant now = Instant.now();
        Instant old = now.minus(31, ChronoUnit.DAYS);
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "old", old));
        recallStore.insertOrIgnore(message("m2", "s2", "u1", 1, "h2", "USER_MESSAGE", "fresh", now));

        recallStore.purgeExpired("u1", 30);

        assertTrue(allInSession("s1").isEmpty(), "the fully-expired file is removed outright");
        assertEquals(1, allInSession("s2").size(), "the fresh session is untouched");
    }

    @Test
    void purgeExpiredLeavesOtherUsersAlone() {
        Instant now = Instant.now();
        Instant old = now.minus(31, ChronoUnit.DAYS);
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "old", old));
        recallStore.insertOrIgnore(message("m2", "s1", "u2", 2, "h2", "USER_MESSAGE", "old", old));

        recallStore.purgeExpired("u1", 30);

        assertEquals(List.of("m2"), ids(allInSession("s1")), "only the requested user's rows expire");
    }

    @Test
    void purgeExpiredAllSweepsEveryUser() {
        Instant now = Instant.now();
        Instant old = now.minus(31, ChronoUnit.DAYS);
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "old", old));
        recallStore.insertOrIgnore(message("m2", "s1", "u1", 2, "h2", "USER_MESSAGE", "fresh", now));
        recallStore.insertOrIgnore(message("m3", "s2", "u2", 1, "h3", "USER_MESSAGE", "old", old));
        recallStore.insertOrIgnore(message("m4", "s2", "u2", 2, "h4", "USER_MESSAGE", "fresh", now));
        recallStore.insertOrIgnore(message("m5", "s3", "u3", 1, "h5", "USER_MESSAGE", "old", old));

        recallStore.purgeExpiredAll(30);

        assertEquals(List.of("m2"), ids(allInSession("s1")), "the mixed file keeps only the fresh row");
        assertEquals(List.of("m4"), ids(allInSession("s2")), "the sweep is not scoped to one user");
        assertTrue(allInSession("s3").isEmpty(), "the fully-expired file is deleted");
    }

    @Test
    void matchesQueryAppliesEveryFilter() {
        Instant now = Instant.now();
        recallStore.insertOrIgnore(message("m1", "s1", "u1", 1, "h1", "USER_MESSAGE", "hello world", now));
        recallStore.insertOrIgnore(message("m2", "s1", "u1", 2, "h2", "AGENT_MESSAGE", "hello agent", now));
        recallStore.insertOrIgnore(message("m3", "s1", "u1", 3, "h3", "COMPACTION_SUMMARY", "hello summary", now));
        recallStore.insertOrIgnore(message("m4", "s1", "u2", 4, "h4", "USER_MESSAGE", "hello other", now));

        assertEquals(
                3,
                recallStore
                        .search(new RecallSearchQuery("u1", null, "s1", 0, true, null))
                        .size());
        assertEquals(
                1,
                recallStore
                        .search(new RecallSearchQuery("u2", null, "s1", 0, true, null))
                        .size());

        assertEquals(4, allInSession("s1").size(), "summaries are included when asked for");
        List<RecallMessageDto> withoutSummaries =
                recallStore.search(new RecallSearchQuery(null, null, "s1", 0, false, null));
        assertEquals(3, withoutSummaries.size());
        assertFalse(withoutSummaries.stream().anyMatch(m -> "COMPACTION_SUMMARY".equals(m.messageType())));

        assertEquals(
                1,
                recallStore
                        .search(new RecallSearchQuery(null, null, "s1", 0, true, Set.of("AGENT_MESSAGE")))
                        .size());
        assertEquals(
                4,
                recallStore
                        .search(new RecallSearchQuery(null, null, "s1", 0, true, Set.of()))
                        .size(),
                "an empty type set is not a filter");

        assertEquals(
                4,
                recallStore
                        .search(new RecallSearchQuery(null, "hello", "s1", 0, true, null))
                        .size());
        assertTrue(recallStore
                .search(new RecallSearchQuery(null, "absent", "s1", 0, true, null))
                .isEmpty());
    }

    // ─── Helpers ───────────────────────────────────────────────

    private List<RecallMessageDto> allInSession(String sessionId) {
        return recallStore.search(new RecallSearchQuery(null, null, sessionId, 0, true, null));
    }

    private static RecallSearchQuery crossSession(String query) {
        return new RecallSearchQuery(null, query, null, 0, true, null);
    }

    private static List<String> ids(List<RecallMessageDto> messages) {
        return messages.stream().map(RecallMessageDto::messageId).toList();
    }

    private static RecallMessageDto message(
            String messageId,
            String sessionId,
            String userId,
            int turnNumber,
            String contentHash,
            String messageType,
            String content,
            Instant createdAt) {
        return new RecallMessageDto(
                messageId,
                sessionId,
                userId,
                turnNumber,
                contentHash,
                "user",
                messageType,
                content,
                null,
                null,
                false,
                false,
                createdAt,
                null);
    }
}
