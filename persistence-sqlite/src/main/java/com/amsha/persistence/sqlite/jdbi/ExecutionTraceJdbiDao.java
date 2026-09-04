package com.amsha.persistence.sqlite.jdbi;

import java.time.Instant;
import java.util.List;

import com.amsha.persistence.api.ExecutionRunSummaryDto;
import com.amsha.persistence.api.ExecutionTraceDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(ExecutionTraceDto.class)
public interface ExecutionTraceJdbiDao {

    String ALL_COLUMNS = """
            event_id AS "eventId",
            run_id AS "runId",
            session_id AS "sessionId",
            turn_number AS "turnNumber",
            sequence,
            stage,
            station,
            status,
            risk_level AS "riskLevel",
            detail,
            prompt_tokens AS "promptTokens",
            completion_tokens AS "completionTokens",
            duration_ms AS "durationMs",
            iteration,
            attempt,
            timestamp,
            paradigm,
            version,
            created_at AS "createdAt"
            """;

    @SqlUpdate(
            "INSERT OR IGNORE INTO execution_trace (event_id, run_id, session_id, turn_number, sequence, stage, station, status, risk_level, detail, prompt_tokens, completion_tokens, duration_ms, iteration, attempt, timestamp, paradigm, version, created_at) "
                    + "VALUES (:eventId, :runId, :sessionId, :turnNumber, :sequence, :stage, :station, :status, :riskLevel, :detail, :promptTokens, :completionTokens, :durationMs, :iteration, :attempt, :timestamp, :paradigm, :version, :createdAt)")
    void save(@BindMethods ExecutionTraceDto dto);

    @SqlQuery("SELECT " + ALL_COLUMNS
            + " FROM execution_trace WHERE run_id = :runId ORDER BY sequence ASC, timestamp ASC")
    List<ExecutionTraceDto> findByRunId(@Bind("runId") String runId);

    @SqlQuery("SELECT " + ALL_COLUMNS + " FROM execution_trace "
            + "WHERE run_id = :runId AND sequence >= :fromSequence AND (:toSequence IS NULL OR sequence <= :toSequence) "
            + "ORDER BY sequence ASC, timestamp ASC LIMIT :limit OFFSET :offset")
    List<ExecutionTraceDto> findByRunIdBounded(
            @Bind("runId") String runId,
            @Bind("fromSequence") long fromSequence,
            @Bind("toSequence") Long toSequence,
            @Bind("offset") int offset,
            @Bind("limit") int limit);

    @SqlQuery("SELECT COUNT(*) FROM execution_trace WHERE run_id = :runId")
    long countByRunId(@Bind("runId") String runId);

    @SqlQuery("SELECT run_id AS \"runId\", session_id AS \"sessionId\", MIN(turn_number) AS \"turnNumber\", "
            + "(SELECT paradigm FROM execution_trace t3 WHERE t3.run_id = execution_trace.run_id AND t3.paradigm IS NOT NULL AND t3.paradigm != '' ORDER BY t3.sequence DESC LIMIT 1) AS \"paradigm\", "
            + "COUNT(*) AS \"eventCount\", MAX(sequence) AS \"maxSequence\", "
            + "(SELECT status FROM execution_trace t2 WHERE t2.run_id = execution_trace.run_id ORDER BY t2.sequence DESC LIMIT 1) AS \"terminalStatus\", "
            + "MIN(timestamp) AS \"startedAt\", "
            + "MAX(CASE WHEN stage = 'turn_end' THEN timestamp ELSE NULL END) AS \"completedAt\" "
            + "FROM execution_trace WHERE session_id = :sessionId "
            + "GROUP BY run_id, session_id "
            + "ORDER BY MAX(timestamp) DESC LIMIT :limit OFFSET :offset")
    @RegisterConstructorMapper(ExecutionRunSummaryDto.class)
    List<ExecutionRunSummaryDto> findRunsBySessionId(
            @Bind("sessionId") String sessionId, @Bind("offset") int offset, @Bind("limit") int limit);

    @SqlUpdate("DELETE FROM execution_trace WHERE created_at < :cutoff")
    int pruneBefore(@Bind("cutoff") Instant cutoff);

    @SqlUpdate("DELETE FROM execution_trace WHERE run_id IN ("
            + "SELECT run_id FROM ("
            + "SELECT run_id, ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY MAX(timestamp) DESC) AS rn "
            + "FROM execution_trace GROUP BY run_id, session_id"
            + ") ranked WHERE ranked.rn > :maxRuns)")
    int pruneExcessRuns(@Bind("maxRuns") int maxRuns);

    @SqlUpdate("DELETE FROM execution_trace WHERE session_id = :sessionId")
    void deleteBySessionId(@Bind("sessionId") String sessionId);
}
