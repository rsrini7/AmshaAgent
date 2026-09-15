package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Companion to {@link FsExecutionTraceStoreAdapterTest}, which covers the save/find/prune happy paths.
 *
 * <p>What it leaves unexecuted: every argument guard ({@code sanitizeId}'s two rejections, the null/blank
 * early returns, the non-positive prune arguments), the two pagination edges, the two
 * {@code removeRunIndexEntries} short-circuits, the {@code latestActivity} backfill, and both
 * {@code resolveSessionId} fallbacks — the durable run-index lookup on a cold cache, and the directory scan
 * used when a run was never recorded in the index. The last two are only reachable from a <em>fresh</em>
 * adapter instance, because the in-memory cache short-circuits them on the one that did the writing.
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsExecutionTraceStoreAdapterDeepTest {

    private static final String TRACE = "trace";
    private static final String RUN_INDEX = "run_index.json";
    private static final String SESSION_INDEX = "index.json";
    private static final String EVENTS = "events.json";

    private Path tempDir;
    private FilesystemFileStore store;
    private FsExecutionTraceStoreAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-trace-deep-");
        store = new FilesystemFileStore(props());
        store.ensureDataDir();
        adapter = new FsExecutionTraceStoreAdapter(store);
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

    // ─── Constructor and argument guards ───────────────────────

    @Test
    void theNoArgConstructorLeavesTheStoreUnset() {
        FsExecutionTraceStoreAdapter unconfigured = new FsExecutionTraceStoreAdapter();

        assertTrue(
                unconfigured.findByRunId("").isEmpty(),
                "a blank runId short-circuits before the null store is ever touched");
    }

    @Test
    void saveRejectsBlankAndPathLikeIds() {
        Instant now = Instant.now();

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.save(event("", "run-1", 1, 0, "stage", now, now)),
                "a blank sessionId is rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.save(event("sess-1", "   ", 1, 0, "stage", now, now)),
                "a blank runId is rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.save(event("sess-1", "../../etc/passwd", 1, 0, "stage", now, now)),
                "a runId carrying a parent reference is rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.save(event("a/b", "run-1", 1, 0, "stage", now, now)),
                "a sessionId carrying a path separator is rejected");
    }

    @Test
    void saveAndSaveBatchIgnoreNullOrEmptyInput() {
        adapter.save(null);
        adapter.saveBatch(null);
        adapter.saveBatch(List.of());

        assertTrue(adapter.findByRunId("no-such-run").isEmpty(), "nothing was persisted");
    }

    @Test
    void saveBatchToleratesNullEntries() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant now = Instant.now();

        adapter.saveBatch(Arrays.asList(null, event(sessionId, runId, 1, 0, "stage", now, now), null));

        assertEquals(1, adapter.countByRunId(runId), "the null entries are skipped");
    }

    // ─── Query edges ───────────────────────────────────────────

    @Test
    void queriesAreEmptyForARunThatCannotBeResolved() {
        assertTrue(adapter.findByRunId("no-such-run").isEmpty());
        assertTrue(adapter.findByRunIdBounded("no-such-run", 0, null, 0, 10).isEmpty());
        assertEquals(0L, adapter.countByRunId("no-such-run"));
    }

    @Test
    void sessionScopedQueriesRejectBlankIds() {
        assertTrue(adapter.findRunsBySessionId("", 0, 10).isEmpty());
        assertTrue(adapter.findRunsBySessionId("   ", 0, 10).isEmpty());
    }

    @Test
    void findRunsBySessionIdReturnsEmptyWhenTheOffsetIsPastTheEnd() {
        String sessionId = "sess-" + uid();
        Instant now = Instant.now();
        adapter.save(event(sessionId, sessionId + ":turn-1", 1, 0, "turn_start", now, now));

        assertEquals(1, adapter.findRunsBySessionId(sessionId, 0, 10).size());
        assertTrue(
                adapter.findRunsBySessionId(sessionId, 5, 10).isEmpty(),
                "an offset past the end returns nothing rather than throwing");
    }

    @Test
    void findByRunIdBoundedClampsTheWindow() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant now = Instant.now();
        adapter.saveBatch(List.of(
                event(sessionId, runId, 1, 0, "stage", now, now),
                event(sessionId, runId, 1, 1, "stage", now, now),
                event(sessionId, runId, 1, 2, "stage", now, now)));

        assertEquals(1, adapter.findByRunIdBounded(runId, 0, null, 0, 0).size(), "a limit of 0 clamps up to 1");
        assertEquals(
                3, adapter.findByRunIdBounded(runId, 0, null, -5, 10).size(), "a negative offset clamps down to 0");
        assertEquals(
                3,
                adapter.findByRunIdBounded(runId, 0, null, 0, 10_000).size(),
                "a limit above the ceiling returns every row");
    }

    // ─── Prune edges ───────────────────────────────────────────

    @Test
    void pruneEntryPointsRejectNonPositiveArguments() {
        assertEquals(0L, adapter.pruneBefore(null), "a null cutoff prunes nothing");
        assertEquals(0L, adapter.pruneExcessRuns(0), "a non-positive cap prunes nothing");
        assertEquals(0L, adapter.pruneExcessRuns(-1));
    }

    @Test
    void pruneExcessRunsSkipsASessionUnderTheCap() {
        String sessionId = "sess-" + uid();
        Instant now = Instant.now();
        adapter.save(event(sessionId, sessionId + ":turn-1", 1, 0, "turn_start", now, now));

        assertEquals(0L, adapter.pruneExcessRuns(100), "a session under the cap is left alone");
        assertEquals(1, adapter.findRunsBySessionId(sessionId, 0, 10).size());
    }

    @Test
    void pruningWithNothingToPruneLeavesTheGlobalRunIndexAlone() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant now = Instant.now();
        adapter.save(event(sessionId, runId, 1, 0, "turn_start", now, now));

        assertEquals(0L, adapter.pruneBefore(now.minus(1, ChronoUnit.DAYS)), "nothing is old enough");

        assertTrue(loadRunIndex().runToSession.containsKey(runId), "the index entry survives");
    }

    @Test
    void deleteBySessionIdRejectsABlankId() {
        adapter.deleteBySessionId("");
        adapter.deleteBySessionId("   ");

        assertTrue(adapter.findRunsBySessionId("", 0, 10).isEmpty());
    }

    @Test
    void deleteBySessionIdToleratesASessionWithNoRunIndex() {
        String sessionId = "sess-" + uid();

        adapter.deleteBySessionId(sessionId);

        assertFalse(store.exists(List.of(TRACE, RUN_INDEX)), "no global index is created for a no-op delete");
    }

    /**
     * Hand-builds a session tree with no global run index, then prunes it. The pruned-run set is non-empty but
     * {@code run_index.json} does not exist, which is the {@code index == null} short-circuit.
     */
    @Test
    void pruningWithoutAGlobalRunIndexStillRemovesTheRun() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);

        writeSessionIndex(sessionId, runId, old, old);
        writeEvents(sessionId, runId, event(sessionId, runId, 1, 0, "stage", old, old));

        assertEquals(1L, adapter.pruneBefore(Instant.now().minus(7, ChronoUnit.DAYS)));

        assertFalse(store.exists(List.of(TRACE, sessionId, runId)), "the run directory is gone");
        assertFalse(store.exists(List.of(TRACE, RUN_INDEX)), "no global index is created as a side effect");
    }

    /**
     * An {@code index.json} written without a {@code latestActivity} field (the shape an older build produced)
     * must be backfilled, and the run ordering must then fall back to {@code completedAt}/{@code startedAt}.
     */
    @Test
    void aSessionIndexWithoutLatestActivityIsBackfilledAndSortedByTimestamp() throws IOException {
        String sessionId = "sess-" + uid();
        Instant now = Instant.now();
        String runA = sessionId + ":turn-1";
        String runB = sessionId + ":turn-2";
        String runC = sessionId + ":turn-3";

        // `latestActivity` must be explicitly null, not merely absent: SessionIndex initialises the field
        // inline, so Jackson leaves the initialiser's map in place when the property is missing.
        Path indexFile = store.resolve(List.of(TRACE, sessionId, SESSION_INDEX));
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, """
                {"latestActivity":null,"runs":[
                  {"runId":"%s","sessionId":"%s","turnNumber":1,"eventCount":1,"maxSequence":0,
                   "terminalStatus":"OK","startedAt":"%s","completedAt":"%s"},
                  {"runId":"%s","sessionId":"%s","turnNumber":2,"eventCount":1,"maxSequence":0,
                   "terminalStatus":"OK","startedAt":"%s","completedAt":null},
                  {"runId":"%s","sessionId":"%s","turnNumber":3,"eventCount":1,"maxSequence":0,
                   "terminalStatus":"OK","startedAt":"%s","completedAt":"%s"}
                ]}""".formatted(
                        runA,
                        sessionId,
                        now.minusSeconds(30),
                        now.minusSeconds(20),
                        runB,
                        sessionId,
                        now.minusSeconds(15),
                        runC,
                        sessionId,
                        now.minusSeconds(10),
                        now.minusSeconds(5)));

        writeEvents(sessionId, runA, event(sessionId, runA, 1, 0, "stage", now, now));
        writeEvents(sessionId, runB, event(sessionId, runB, 2, 0, "stage", now, now));
        writeEvents(sessionId, runC, event(sessionId, runC, 3, 0, "stage", now, now));

        // Recency order from completedAt/startedAt: runC (-5s) > runB (-15s, no turn_end) > runA (-20s).
        assertEquals(2L, adapter.pruneExcessRuns(1), "the two least-recent runs are pruned");

        assertEquals(
                List.of(runC),
                adapter.findRunsBySessionId(sessionId, 0, 10).stream()
                        .map(ExecutionRunSummaryDto::runId)
                        .toList());
    }

    // ─── resolveSessionId fallbacks ────────────────────────────

    @Test
    void resolveSessionIdUsesTheDurableRunIndexOnAColdCache() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant now = Instant.now();
        adapter.save(event(sessionId, runId, 1, 0, "turn_start", now, now));

        // A fresh instance has an empty in-memory cache, so the mapping must come off disk.
        FsExecutionTraceStoreAdapter restarted = new FsExecutionTraceStoreAdapter(store);

        assertEquals(1, restarted.findByRunId(runId).size(), "the run is resolved from run_index.json");
    }

    @Test
    void resolveSessionIdFallsBackToScanningSessionDirectories() {
        String firstSession = "a-sess-" + uid();
        String secondSession = "z-sess-" + uid();
        // No ":turn-" suffix, so the session cannot be derived from the runId — only a directory scan finds it.
        String orphanRun = "orphan-" + uid();
        Instant now = Instant.now();

        writeEvents(firstSession, "other-run", event(firstSession, "other-run", 1, 0, "stage", now, now));
        writeEvents(secondSession, orphanRun, event(secondSession, orphanRun, 1, 0, "stage", now, now));

        FsExecutionTraceStoreAdapter restarted = new FsExecutionTraceStoreAdapter(store);

        assertEquals(1, restarted.findByRunId(orphanRun).size(), "the run is located by directory scan");
    }

    @Test
    void resolveSessionIdReturnsNullWhenNoSessionHoldsTheRun() {
        writeEvents(
                "sess-" + uid(), "some-run", event("sess-x", "some-run", 1, 0, "stage", Instant.now(), Instant.now()));

        FsExecutionTraceStoreAdapter restarted = new FsExecutionTraceStoreAdapter(store);

        assertTrue(restarted.findByRunId("absent-run").isEmpty());
    }

    // ─── Helpers ───────────────────────────────────────────────

    private FsExecutionTraceStoreAdapter.RunIndex loadRunIndex() {
        return store.readOptional(List.of(TRACE, RUN_INDEX), FsExecutionTraceStoreAdapter.RunIndex.class)
                .orElseGet(FsExecutionTraceStoreAdapter.RunIndex::new);
    }

    private void writeEvents(String sessionId, String runId, ExecutionTraceDto... dtos) {
        FsExecutionTraceStoreAdapter.RunEvents runEvents = new FsExecutionTraceStoreAdapter.RunEvents();
        runEvents.events.addAll(List.of(dtos));
        store.write(List.of(TRACE, sessionId, runId, EVENTS), runEvents);
    }

    private void writeSessionIndex(String sessionId, String runId, Instant startedAt, Instant completedAt) {
        FsExecutionTraceStoreAdapter.SessionIndex index = new FsExecutionTraceStoreAdapter.SessionIndex();
        index.runs.add(new ExecutionRunSummaryDto(runId, sessionId, 1, null, 1, 0, "OK", startedAt, completedAt));
        store.write(List.of(TRACE, sessionId, SESSION_INDEX), index);
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    private static ExecutionTraceDto event(
            String sessionId,
            String runId,
            int turnNumber,
            long sequence,
            String stage,
            Instant timestamp,
            Instant createdAt) {
        return new ExecutionTraceDto(
                uid(),
                runId,
                sessionId,
                turnNumber,
                sequence,
                stage,
                null,
                "OK",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                timestamp,
                null,
                1,
                createdAt);
    }

    private FsStoreProperties props() {
        return new FsStoreProperties() {
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
    }
}
