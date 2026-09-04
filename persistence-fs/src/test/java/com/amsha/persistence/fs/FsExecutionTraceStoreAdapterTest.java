package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FsExecutionTraceStoreAdapterTest {

    private static final String TRACE_DOMAIN = "trace";
    private static final String RUN_INDEX_FILE = "run_index.json";

    @TempDir
    File tempDir;

    private FilesystemFileStore store;
    private FsExecutionTraceStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        FsStoreProperties props = mock(FsStoreProperties.class);
        when(props.dataDir()).thenReturn(tempDir.getAbsolutePath());
        store = new FilesystemFileStore(props);
        adapter = new FsExecutionTraceStoreAdapter(store);
    }

    private FsExecutionTraceStoreAdapter.RunIndex loadGlobalRunIndex() {
        return store.readOptional(List.of(TRACE_DOMAIN, RUN_INDEX_FILE), FsExecutionTraceStoreAdapter.RunIndex.class)
                .orElseGet(FsExecutionTraceStoreAdapter.RunIndex::new);
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    private ExecutionTraceDto makeDto(String sessionId, String runId, int turnNumber, long sequence, Instant ts) {
        return new ExecutionTraceDto(
                uid(),
                runId,
                sessionId,
                turnNumber,
                sequence,
                "turn_start",
                "station_01_inbound",
                "RUNNING",
                "LOW",
                "Turn initialized",
                10,
                20,
                150L,
                1,
                1,
                ts,
                "REACT",
                1,
                Instant.now());
    }

    @Test
    void testSaveAndFindByRunIdOrder() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant now = Instant.now();

        // Insert out of order: sequence 2, then 0, then 1
        adapter.save(makeDto(sessionId, runId, 1, 2, now.plusMillis(200)));
        adapter.save(makeDto(sessionId, runId, 1, 0, now));
        adapter.save(makeDto(sessionId, runId, 1, 1, now.plusMillis(100)));

        List<ExecutionTraceDto> events = adapter.findByRunId(runId);
        assertEquals(3, events.size());
        assertEquals(0, events.get(0).sequence());
        assertEquals(1, events.get(1).sequence());
        assertEquals(2, events.get(2).sequence());
        assertEquals(sessionId, events.get(0).sessionId());
    }

    @Test
    void testSaveDuplicateEventIdIsIdempotent() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        String eventId = uid();
        Instant now = Instant.now();

        ExecutionTraceDto dto1 = new ExecutionTraceDto(
                eventId,
                runId,
                sessionId,
                1,
                0,
                "turn_start",
                null,
                "OK",
                null,
                "first",
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                1,
                now);

        ExecutionTraceDto dto2 = new ExecutionTraceDto(
                eventId,
                runId,
                sessionId,
                1,
                0,
                "turn_start",
                null,
                "OK",
                null,
                "duplicate",
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                1,
                now);

        adapter.save(dto1);
        adapter.save(dto2);

        List<ExecutionTraceDto> events = adapter.findByRunId(runId);
        assertEquals(1, events.size());
        assertEquals(eventId, events.get(0).eventId());
    }

    @Test
    void testSaveBatchAndBoundedQuery() {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        Instant now = Instant.now();

        List<ExecutionTraceDto> batch = List.of(
                makeDto(sessionId, runId, 1, 0, now),
                makeDto(sessionId, runId, 1, 1, now.plusMillis(10)),
                makeDto(sessionId, runId, 1, 2, now.plusMillis(20)),
                makeDto(sessionId, runId, 1, 3, now.plusMillis(30)),
                makeDto(sessionId, runId, 1, 4, now.plusMillis(40)));

        adapter.saveBatch(batch);
        assertEquals(5, adapter.countByRunId(runId));

        // Query sequence 1 to 3
        List<ExecutionTraceDto> bounded = adapter.findByRunIdBounded(runId, 1, 3L, 0, 10);
        assertEquals(3, bounded.size());
        assertEquals(1, bounded.get(0).sequence());
        assertEquals(2, bounded.get(1).sequence());
        assertEquals(3, bounded.get(2).sequence());

        // Test pagination offset & limit
        List<ExecutionTraceDto> paged = adapter.findByRunIdBounded(runId, 0, null, 2, 2);
        assertEquals(2, paged.size());
        assertEquals(2, paged.get(0).sequence());
        assertEquals(3, paged.get(1).sequence());
    }

    @Test
    void testFindRunsBySessionId() {
        String sessionId = "sess-" + uid();
        String run1 = sessionId + ":turn-1";
        String run2 = sessionId + ":turn-2";
        Instant now = Instant.now();

        // run1 starts earlier at -10s
        adapter.save(makeDto(sessionId, run1, 1, 0, now.minus(10, ChronoUnit.SECONDS)));
        // run2 starts at -5s and has no turn_end (incomplete run)
        adapter.save(makeDto(sessionId, run2, 2, 0, now.minus(5, ChronoUnit.SECONDS)));
        // run1 gets a turn_end event at -1s (latest activity!)
        ExecutionTraceDto turnEndDto = new ExecutionTraceDto(
                uid(),
                run1,
                sessionId,
                1,
                1,
                "turn_end",
                "station_10_outbound",
                "COMPLETED",
                "LOW",
                "Turn completed",
                20,
                30,
                200L,
                1,
                1,
                now.minus(1, ChronoUnit.SECONDS),
                "REACT",
                1,
                Instant.now());
        adapter.save(turnEndDto);

        List<ExecutionRunSummaryDto> runs = adapter.findRunsBySessionId(sessionId, 0, 10);
        assertEquals(2, runs.size());
        // Ordered by latest activity DESC -> run1 first (activity at -1s), then run2 (activity at -5s)
        assertEquals(run1, runs.get(0).runId());
        assertEquals(2, runs.get(0).eventCount());
        assertNotNull(runs.get(0).completedAt());
        assertEquals(
                turnEndDto.timestamp().toEpochMilli(), runs.get(0).completedAt().toEpochMilli());

        // run2 has no turn_end, completedAt must be null
        assertEquals(run2, runs.get(1).runId());
        assertEquals(1, runs.get(1).eventCount());
        assertNull(runs.get(1).completedAt());
    }

    @Test
    void testPruneExcessRuns() {
        String sessionId = "sess-" + uid();
        Instant now = Instant.now();

        // Create 3 runs for sessionId
        for (int i = 1; i <= 3; i++) {
            String runId = sessionId + ":turn-" + i;
            adapter.save(makeDto(sessionId, runId, i, 0, now.minus(10 - i, ChronoUnit.SECONDS)));
        }

        // Limit to 2 runs per session: run1 (oldest activity) should be pruned
        long pruned = adapter.pruneExcessRuns(2);
        assertTrue(pruned >= 1);

        List<ExecutionRunSummaryDto> remaining = adapter.findRunsBySessionId(sessionId, 0, 10);
        assertEquals(2, remaining.size());
        assertEquals(sessionId + ":turn-3", remaining.get(0).runId());
        assertEquals(sessionId + ":turn-2", remaining.get(1).runId());
    }

    @Test
    void testPruneBeforeAndDeleteBySessionId() {
        String sessionId1 = "sess-" + uid();
        String sessionId2 = "sess-" + uid();
        String run1 = sessionId1 + ":turn-1";
        String run2 = sessionId2 + ":turn-1";

        Instant oldTs = Instant.now().minus(30, ChronoUnit.DAYS);
        ExecutionTraceDto oldEvent = new ExecutionTraceDto(
                uid(),
                run1,
                sessionId1,
                1,
                0,
                "stage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                oldTs,
                null,
                1,
                oldTs);

        Instant recentTs = Instant.now();
        ExecutionTraceDto recentEvent = new ExecutionTraceDto(
                uid(),
                run2,
                sessionId2,
                1,
                0,
                "stage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                recentTs,
                null,
                1,
                recentTs);

        adapter.save(oldEvent);
        adapter.save(recentEvent);

        long pruned = adapter.pruneBefore(Instant.now().minus(7, ChronoUnit.DAYS));
        assertTrue(pruned >= 1);
        assertEquals(0, adapter.countByRunId(run1));
        assertEquals(1, adapter.countByRunId(run2));

        adapter.deleteBySessionId(sessionId2);
        assertEquals(0, adapter.countByRunId(run2));
    }

    @Test
    void testPruneRemovesRunIndexEntries() {
        String sessionId = "sess-" + uid();
        String expiredRun = sessionId + ":turn-1";
        String retainedRun = sessionId + ":turn-2";
        String partialRun = sessionId + ":turn-3";
        Instant now = Instant.now();
        Instant oldTs = now.minus(30, ChronoUnit.DAYS);

        // makeDto() hardcodes createdAt = now, so build events with explicit createdAt to
        // exercise per-event pruning (SQL deletes WHERE created_at < cutoff).
        ExecutionTraceDto expiredEvent = new ExecutionTraceDto(
                uid(),
                expiredRun,
                sessionId,
                1,
                0,
                "stage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                oldTs,
                null,
                1,
                oldTs);
        ExecutionTraceDto retainedEvent = new ExecutionTraceDto(
                uid(),
                retainedRun,
                sessionId,
                2,
                0,
                "stage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                1,
                now);
        // partialRun has one old and one recent event: only the old one is pruned.
        ExecutionTraceDto partialOld = new ExecutionTraceDto(
                uid(),
                partialRun,
                sessionId,
                3,
                0,
                "stage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                oldTs,
                null,
                1,
                oldTs);
        ExecutionTraceDto partialNew = new ExecutionTraceDto(
                uid(),
                partialRun,
                sessionId,
                3,
                1,
                "stage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                1,
                now);

        adapter.save(expiredEvent);
        adapter.save(retainedEvent);
        adapter.save(partialOld);
        adapter.save(partialNew);

        assertTrue(loadGlobalRunIndex().runToSession.containsKey(expiredRun));
        assertTrue(loadGlobalRunIndex().runToSession.containsKey(retainedRun));
        assertTrue(loadGlobalRunIndex().runToSession.containsKey(partialRun));

        adapter.pruneBefore(now.minus(7, ChronoUnit.DAYS));

        // Regression guard: the global run index used to keep entries for pruned runs
        // forever, growing without bound and slowing every subsequent batch write.
        assertFalse(
                loadGlobalRunIndex().runToSession.containsKey(expiredRun),
                "pruned run (all events expired) must be dropped from the global run index");
        assertTrue(
                loadGlobalRunIndex().runToSession.containsKey(retainedRun),
                "retained run must stay in the global run index");
        // Per-event pruning: the run survives, but only the recent event remains.
        assertTrue(
                loadGlobalRunIndex().runToSession.containsKey(partialRun),
                "run with a surviving event must stay in the global run index");
        assertEquals(1, adapter.countByRunId(partialRun), "only the recent event should survive per-event pruning");
    }

    @Test
    void testDeleteBySessionIdRemovesRunIndexEntries() {
        String sessionId = "sess-" + uid();
        String run1 = sessionId + ":turn-1";
        Instant now = Instant.now();

        adapter.save(makeDto(sessionId, run1, 1, 0, now));
        assertTrue(loadGlobalRunIndex().runToSession.containsKey(run1));

        adapter.deleteBySessionId(sessionId);

        assertFalse(
                loadGlobalRunIndex().runToSession.containsKey(run1),
                "run belonging to a deleted session must be dropped from the global run index");
    }

    @Test
    void testConcurrentSavesToSameRunAreNotLost() throws Exception {
        String sessionId = "sess-" + uid();
        String runId = sessionId + ":turn-1";
        int threadCount = 8;
        int perThread = 25;
        int total = threadCount * perThread;
        Instant now = Instant.now();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                int base = t * perThread;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        int sequence = base + i;
                        adapter.save(makeDto(sessionId, runId, 1, sequence, now.plusMillis(sequence)));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "writers failed to start");
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Every event has a unique eventId and sequence, so a lost update on the shared
        // events.json would show up here as a short count.
        assertEquals(total, adapter.countByRunId(runId));
    }
}
