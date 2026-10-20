package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;
import com.amsha.persistence.api.PersistenceBackend;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsExecutionTraceStoreAdapter implements ExecutionTraceStorePort {

    private static final Logger log = Logger.getLogger(FsExecutionTraceStoreAdapter.class);
    private static final String DOMAIN = "trace";
    private static final String RUN_INDEX_FILE = "run_index.json";
    private static final String EVENTS_FILE = "events.json";
    private static final String SESSION_INDEX_FILE = "index.json";
    private static final int RUN_LOCK_STRIPES = 64;

    private final FilesystemFileStore store;
    private final ConcurrentHashMap<String, String> runToSessionCache = new ConcurrentHashMap<>();
    private final Object[] runLocks = newStripedLocks();
    private final Object runIndexLock = new Object();

    @Inject
    public FsExecutionTraceStoreAdapter(FilesystemFileStore store) {
        this.store = store;
    }

    protected FsExecutionTraceStoreAdapter() {
        this(null);
    }

    private static Object[] newStripedLocks() {
        Object[] locks = new Object[RUN_LOCK_STRIPES];
        for (int i = 0; i < RUN_LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /**
     * Returns the stripe lock guarding a single run.
     *
     * <p>The stripe set is fixed for the lifetime of the bean. Evicting lock objects from a
     * bounded map (the previous behaviour) discards monitors that in-flight threads already
     * hold, so two threads end up synchronizing on different objects for the same runId and
     * the read-modify-write of events.json interleaves, losing events. Striping trades a
     * small amount of false sharing for a lock that can never be invalidated.
     */
    private Object lockFor(String runId) {
        int stripe = Math.floorMod(runId == null ? 0 : runId.hashCode(), RUN_LOCK_STRIPES);
        return runLocks[stripe];
    }

    /**
     * Guards against path traversal: sessionId/runId are used verbatim as on-disk path
     * segments. Rejects null/blank ids and any id carrying separator or parent-reference
     * characters. App-generated ids (UUID-based, possibly containing ':') pass through.
     */
    private static String sanitizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("execution trace id must not be null or blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("execution trace id contains illegal characters: " + id);
        }
        return id;
    }

    @Override
    public void save(ExecutionTraceDto dto) {
        if (dto == null) {
            return;
        }
        saveBatch(List.of(dto));
    }

    @Override
    public void saveBatch(List<ExecutionTraceDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }

        Map<String, List<ExecutionTraceDto>> grouped = new ConcurrentHashMap<>();
        for (ExecutionTraceDto dto : dtos) {
            if (dto != null) {
                grouped.computeIfAbsent(dto.runId(), _ -> new ArrayList<>()).add(dto);
            }
        }

        for (Map.Entry<String, List<ExecutionTraceDto>> entry : grouped.entrySet()) {
            String runId = entry.getKey();
            List<ExecutionTraceDto> batch = entry.getValue();
            if (batch.isEmpty()) {
                continue;
            }
            String sessionId = batch.get(0).sessionId();
            processRunBatch(sessionId, runId, batch);
        }
    }

    private void processRunBatch(String sessionId, String runId, List<ExecutionTraceDto> batch) {
        sessionId = sanitizeId(sessionId);
        runId = sanitizeId(runId);
        synchronized (lockFor(runId)) {
            RunEvents runEvents = loadRunEvents(sessionId, runId);
            boolean updated = false;

            for (ExecutionTraceDto dto : batch) {
                boolean alreadyExists =
                        runEvents.events.stream().anyMatch(e -> Objects.equals(e.eventId(), dto.eventId()));
                if (!alreadyExists) {
                    runEvents.events.add(dto);
                    updated = true;
                }
            }

            if (!updated) {
                return;
            }

            runEvents.events.sort(
                    Comparator.comparingLong(ExecutionTraceDto::sequence).thenComparing(ExecutionTraceDto::timestamp));

            store.write(List.of(DOMAIN, sessionId, runId, EVENTS_FILE), runEvents);
            updateSessionIndex(sessionId, runId, runEvents.events);
            recordRunToSession(runId, sessionId);
        }
    }

    private void updateSessionIndex(String sessionId, String runId, List<ExecutionTraceDto> events) {
        if (events.isEmpty()) {
            return;
        }
        SessionIndex index = loadSessionIndex(sessionId);
        ExecutionTraceDto first = events.get(0);
        ExecutionTraceDto last = events.get(events.size() - 1);

        String paradigm = null;
        Instant completedAt = null;
        for (int i = events.size() - 1; i >= 0; i--) {
            ExecutionTraceDto e = events.get(i);
            if (paradigm == null && e.paradigm() != null && !e.paradigm().isBlank()) {
                paradigm = e.paradigm();
            }
            if (completedAt == null && "turn_end".equalsIgnoreCase(e.stage())) {
                completedAt = e.timestamp();
            }
            if (paradigm != null && completedAt != null) {
                break;
            }
        }

        ExecutionRunSummaryDto summary = new ExecutionRunSummaryDto(
                runId,
                sessionId,
                first.turnNumber(),
                paradigm,
                events.size(),
                last.sequence(),
                last.status(),
                first.timestamp(),
                completedAt);

        index.runs.removeIf(r -> Objects.equals(r.runId(), runId));
        index.runs.add(summary);
        index.latestActivity.put(runId, last.timestamp());
        index.runs.sort((a, b) -> {
            Instant aTime = getRunLatestActivity(index, a);
            Instant bTime = getRunLatestActivity(index, b);
            return bTime.compareTo(aTime);
        });

        store.write(List.of(DOMAIN, sessionId, SESSION_INDEX_FILE), index);
    }

    private static Instant getRunLatestActivity(SessionIndex index, ExecutionRunSummaryDto run) {
        if (index != null && index.latestActivity != null) {
            Instant t = index.latestActivity.get(run.runId());
            if (t != null) {
                return t;
            }
        }
        return run.completedAt() != null ? run.completedAt() : run.startedAt();
    }

    @Override
    public List<ExecutionTraceDto> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        runId = sanitizeId(runId);
        String sessionId = resolveSessionId(runId);
        if (sessionId == null) {
            return List.of();
        }
        sessionId = sanitizeId(sessionId);
        return List.copyOf(loadRunEvents(sessionId, runId).events);
    }

    @Override
    public List<ExecutionTraceDto> findByRunIdBounded(
            String runId, long fromSequence, Long toSequence, int offset, int limit) {
        List<ExecutionTraceDto> all = findByRunId(runId);
        if (all.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = Math.min(500, Math.max(1, limit));
        int effectiveOffset = Math.max(0, offset);

        return all.stream()
                .filter(e -> e.sequence() >= fromSequence)
                .filter(e -> toSequence == null || e.sequence() <= toSequence)
                .skip(effectiveOffset)
                .limit(effectiveLimit)
                .toList();
    }

    @Override
    public long countByRunId(String runId) {
        return findByRunId(runId).size();
    }

    @Override
    public List<ExecutionRunSummaryDto> findRunsBySessionId(String sessionId, int offset, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        sessionId = sanitizeId(sessionId);
        SessionIndex index = loadSessionIndex(sessionId);
        int effectiveLimit = Math.min(100, Math.max(1, limit));
        int effectiveOffset = Math.max(0, offset);

        if (effectiveOffset >= index.runs.size()) {
            return List.of();
        }
        int end = Math.min(effectiveOffset + effectiveLimit, index.runs.size());
        return List.copyOf(index.runs.subList(effectiveOffset, end));
    }

    @Override
    public long pruneBefore(Instant cutoff) {
        if (cutoff == null) {
            return 0L;
        }
        long deletedCount = 0;
        List<String> sessions = store.listDirs(List.of(DOMAIN));
        Set<String> prunedRunIds = new HashSet<>();

        for (String rawSessionId : sessions) {
            String sessionId = sanitizeId(rawSessionId);
            SessionIndex index = loadSessionIndex(sessionId);
            List<ExecutionRunSummaryDto> remainingRuns = new ArrayList<>();

            for (ExecutionRunSummaryDto run : index.runs) {
                String runId = sanitizeId(run.runId());
                // Hold the run stripe so a concurrent save cannot resurrect the run
                // directory after it has been pruned.
                synchronized (lockFor(runId)) {
                    RunEvents runEvents = loadRunEvents(sessionId, runId);
                    List<ExecutionTraceDto> events = runEvents.events;

                    long removed = 0;
                    for (ExecutionTraceDto e : events) {
                        if (e.createdAt() != null && e.createdAt().isBefore(cutoff)) {
                            removed++;
                        }
                    }
                    if (removed == 0) {
                        remainingRuns.add(run);
                        continue;
                    }

                    List<ExecutionTraceDto> kept = new ArrayList<>();
                    for (ExecutionTraceDto e : events) {
                        if (e.createdAt() == null || !e.createdAt().isBefore(cutoff)) {
                            kept.add(e);
                        }
                    }
                    deletedCount += removed;

                    if (kept.isEmpty()) {
                        // Whole run pruned: drop the directory and its global index entry.
                        store.deleteDirectory(List.of(DOMAIN, sessionId, runId));
                        runToSessionCache.remove(runId);
                        if (index.latestActivity != null) {
                            index.latestActivity.remove(runId);
                        }
                        prunedRunIds.add(runId);
                    } else {
                        runEvents.events = kept;
                        runEvents.events.sort(Comparator.comparingLong(ExecutionTraceDto::sequence)
                                .thenComparing(ExecutionTraceDto::timestamp));
                        store.write(List.of(DOMAIN, sessionId, runId, EVENTS_FILE), runEvents);
                        updateSessionIndex(sessionId, runId, runEvents.events);
                        remainingRuns.add(run);
                    }
                }
            }

            if (remainingRuns.size() != index.runs.size()) {
                index.runs = remainingRuns;
                store.write(List.of(DOMAIN, sessionId, SESSION_INDEX_FILE), index);
            }
        }
        removeRunIndexEntries(prunedRunIds);
        return deletedCount;
    }

    @Override
    public long pruneExcessRuns(int maxRunsPerSession) {
        if (maxRunsPerSession <= 0) {
            return 0L;
        }
        long deletedCount = 0;
        List<String> sessions = store.listDirs(List.of(DOMAIN));
        Set<String> prunedRunIds = new HashSet<>();

        for (String rawSessionId : sessions) {
            String sessionId = sanitizeId(rawSessionId);
            SessionIndex index = loadSessionIndex(sessionId);
            if (index.runs.size() <= maxRunsPerSession) {
                continue;
            }

            index.runs.sort((a, b) -> {
                Instant aTime = getRunLatestActivity(index, a);
                Instant bTime = getRunLatestActivity(index, b);
                return bTime.compareTo(aTime);
            });

            List<ExecutionRunSummaryDto> toKeep = new ArrayList<>(index.runs.subList(0, maxRunsPerSession));
            List<ExecutionRunSummaryDto> toPrune =
                    new ArrayList<>(index.runs.subList(maxRunsPerSession, index.runs.size()));

            for (ExecutionRunSummaryDto excessRun : toPrune) {
                String runId = sanitizeId(excessRun.runId());
                synchronized (lockFor(runId)) {
                    RunEvents events = loadRunEvents(sessionId, runId);
                    deletedCount += events.events.size();
                    store.deleteDirectory(List.of(DOMAIN, sessionId, runId));
                    runToSessionCache.remove(runId);
                    if (index.latestActivity != null) {
                        index.latestActivity.remove(runId);
                    }
                    prunedRunIds.add(runId);
                }
            }

            index.runs = toKeep;
            store.write(List.of(DOMAIN, sessionId, SESSION_INDEX_FILE), index);
        }

        removeRunIndexEntries(prunedRunIds);
        return deletedCount;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionId = sanitizeId(sessionId);
        SessionIndex index = loadSessionIndex(sessionId);
        Set<String> deletedRunIds = new HashSet<>();
        for (ExecutionRunSummaryDto run : index.runs) {
            runToSessionCache.remove(run.runId());
            deletedRunIds.add(run.runId());
        }
        store.deleteDirectory(List.of(DOMAIN, sessionId));
        removeRunIndexEntries(deletedRunIds);
    }

    /**
     * Drops durable runId to sessionId mappings for runs that no longer exist.
     *
     * <p>Without this the global run index grows without bound: entries survive every prune
     * and session deletion, and {@link #recordRunToSession} re-reads the whole index on each
     * batch, so write cost scales with the number of runs ever recorded rather than the
     * batch size. The read-modify-write is serialized on {@link #runIndexLock}; callers must
     * not hold a run stripe lock.
     */
    private void removeRunIndexEntries(Set<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return;
        }
        synchronized (runIndexLock) {
            RunIndex index = store.readOptional(List.of(DOMAIN, RUN_INDEX_FILE), RunIndex.class)
                    .orElse(null);
            if (index == null || index.runToSession.isEmpty()) {
                return;
            }
            if (index.runToSession.keySet().removeAll(runIds)) {
                store.write(List.of(DOMAIN, RUN_INDEX_FILE), index);
            }
        }
    }

    private RunEvents loadRunEvents(String sessionId, String runId) {
        return store.readOptional(List.of(DOMAIN, sessionId, runId, EVENTS_FILE), RunEvents.class)
                .orElseGet(RunEvents::new);
    }

    private SessionIndex loadSessionIndex(String sessionId) {
        SessionIndex index = store.readOptional(List.of(DOMAIN, sessionId, SESSION_INDEX_FILE), SessionIndex.class)
                .orElseGet(SessionIndex::new);
        if (index.latestActivity == null) {
            index.latestActivity = new ConcurrentHashMap<>();
        }
        return index;
    }

    private void recordRunToSession(String runId, String sessionId) {
        // put() returns the previous mapping. When it already matches, the durable index is
        // known to be current for this run, so the O(total runs) index read is skipped. This
        // matters because every batch write for an established run would otherwise re-read
        // the entire index.
        if (sessionId.equals(runToSessionCache.put(runId, sessionId))) {
            return;
        }
        synchronized (runIndexLock) {
            RunIndex index = store.readOptional(List.of(DOMAIN, RUN_INDEX_FILE), RunIndex.class)
                    .orElseGet(RunIndex::new);
            if (!sessionId.equals(index.runToSession.get(runId))) {
                index.runToSession.put(runId, sessionId);
                store.write(List.of(DOMAIN, RUN_INDEX_FILE), index);
            }
        }
    }

    private String resolveSessionId(String runId) {
        String cached = runToSessionCache.get(runId);
        if (cached != null) {
            return cached;
        }

        RunIndex index = store.readOptional(List.of(DOMAIN, RUN_INDEX_FILE), RunIndex.class)
                .orElse(null);
        if (index != null && index.runToSession.containsKey(runId)) {
            String resolved = index.runToSession.get(runId);
            runToSessionCache.put(runId, resolved);
            return resolved;
        }

        int turnIdx = runId.lastIndexOf(":turn-");
        if (turnIdx > 0) {
            String derived = runId.substring(0, turnIdx);
            runToSessionCache.put(runId, derived);
            return derived;
        }

        List<String> sessions = store.listDirs(List.of(DOMAIN));
        for (String sess : sessions) {
            if (store.readOptional(List.of(DOMAIN, sess, runId, EVENTS_FILE), RunEvents.class)
                    .isPresent()) {
                runToSessionCache.put(runId, sess);
                return sess;
            }
        }
        return null;
    }

    public static class RunEvents {
        public List<ExecutionTraceDto> events = new ArrayList<>();
    }

    public static class SessionIndex {
        public List<ExecutionRunSummaryDto> runs = new ArrayList<>();
        public Map<String, Instant> latestActivity = new ConcurrentHashMap<>();
    }

    public static class RunIndex {
        public Map<String, String> runToSession = new ConcurrentHashMap<>();
    }
}
