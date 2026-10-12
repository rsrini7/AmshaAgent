package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;
import com.amsha.persistence.api.ExecutionTraceStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.postgres.jdbi.ExecutionTraceJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
public class PostgresExecutionTraceStoreAdapter implements ExecutionTraceStorePort {

    private static final Logger log = Logger.getLogger(PostgresExecutionTraceStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public PostgresExecutionTraceStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresExecutionTraceStoreAdapter() {
        this(null);
    }

    @Override
    public void save(ExecutionTraceDto dto) {
        log.tracef("save trace event id=%s runId=%s seq=%d", dto.eventId(), dto.runId(), dto.sequence());
        jdbi.useExtension(ExecutionTraceJdbiDao.class, dao -> dao.save(dto));
    }

    @Override
    public void saveBatch(List<ExecutionTraceDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        log.tracef("saveBatch trace events count=%d", dtos.size());
        jdbi.useHandle(handle -> {
            var batch = handle.prepareBatch(
                    "INSERT INTO execution_trace (event_id, run_id, session_id, turn_number, sequence, stage, station, status, risk_level, detail, prompt_tokens, completion_tokens, duration_ms, iteration, attempt, timestamp, paradigm, version, created_at) "
                            + "VALUES (:eventId, :runId, :sessionId, :turnNumber, :sequence, :stage, :station, :status, :riskLevel, :detail, :promptTokens, :completionTokens, :durationMs, :iteration, :attempt, :timestamp, :paradigm, :version, :createdAt) "
                            + "ON CONFLICT (event_id) DO NOTHING");
            for (ExecutionTraceDto dto : dtos) {
                batch.bindMethods(dto).add();
            }
            batch.execute();
        });
    }

    @Override
    public List<ExecutionTraceDto> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return jdbi.withExtension(ExecutionTraceJdbiDao.class, dao -> dao.findByRunId(runId));
    }

    @Override
    public List<ExecutionTraceDto> findByRunIdBounded(
            String runId, long fromSequence, Long toSequence, int offset, int limit) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = Math.min(500, Math.max(1, limit));
        int effectiveOffset = Math.max(0, offset);
        return jdbi.withExtension(
                ExecutionTraceJdbiDao.class,
                dao -> dao.findByRunIdBounded(runId, fromSequence, toSequence, effectiveOffset, effectiveLimit));
    }

    @Override
    public long countByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return 0L;
        }
        return jdbi.withExtension(ExecutionTraceJdbiDao.class, dao -> dao.countByRunId(runId));
    }

    @Override
    public List<ExecutionRunSummaryDto> findRunsBySessionId(String sessionId, int offset, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = Math.min(100, Math.max(1, limit));
        int effectiveOffset = Math.max(0, offset);
        return jdbi.withExtension(
                ExecutionTraceJdbiDao.class,
                dao -> dao.findRunsBySessionId(sessionId, effectiveOffset, effectiveLimit));
    }

    @Override
    public long pruneBefore(Instant cutoff) {
        if (cutoff == null) {
            return 0L;
        }
        return jdbi.withExtension(ExecutionTraceJdbiDao.class, dao -> (long) dao.pruneBefore(cutoff));
    }

    @Override
    public long pruneExcessRuns(int maxRunsPerSession) {
        if (maxRunsPerSession <= 0) {
            return 0L;
        }
        return jdbi.withExtension(ExecutionTraceJdbiDao.class, dao -> (long) dao.pruneExcessRuns(maxRunsPerSession));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        jdbi.useExtension(ExecutionTraceJdbiDao.class, dao -> dao.deleteBySessionId(sessionId));
    }
}
