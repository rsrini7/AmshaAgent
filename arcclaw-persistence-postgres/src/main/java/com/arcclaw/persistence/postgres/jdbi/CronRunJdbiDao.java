package com.arcclaw.persistence.postgres.jdbi;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CronRunDto;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(CronRunDto.class)
public interface CronRunJdbiDao {

    @SqlUpdate("INSERT INTO agent_cron_run (id, cronId, sessionId, status, startedAt, finishedAt, errorReason, iterationsUsed, inputTokens, outputTokens, usdCost, createdAt) VALUES (:id, :cronId, :sessionId, :status, :startedAt, :finishedAt, :errorReason, :iterationsUsed, :inputTokens, :outputTokens, :usdCost, :createdAt) ON CONFLICT (id) DO UPDATE SET cronId = EXCLUDED.cronId, sessionId = EXCLUDED.sessionId, status = EXCLUDED.status, startedAt = EXCLUDED.startedAt, finishedAt = EXCLUDED.finishedAt, errorReason = EXCLUDED.errorReason, iterationsUsed = EXCLUDED.iterationsUsed, inputTokens = EXCLUDED.inputTokens, outputTokens = EXCLUDED.outputTokens, usdCost = EXCLUDED.usdCost")
    void save(@BindMethods CronRunDto dto);

    @SqlQuery("SELECT id, cronId, sessionId, status, startedAt, finishedAt, errorReason, iterationsUsed, inputTokens, outputTokens, usdCost, createdAt FROM agent_cron_run WHERE id = :id")
    Optional<CronRunDto> findById(@Bind("id") String id);

    @SqlQuery("SELECT id, cronId, sessionId, status, startedAt, finishedAt, errorReason, iterationsUsed, inputTokens, outputTokens, usdCost, createdAt FROM agent_cron_run WHERE cronId = :cronId ORDER BY startedAt DESC LIMIT :limit OFFSET :offset")
    List<CronRunDto> findByCronId(@Bind("cronId") String cronId, @Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery("SELECT COUNT(*) FROM agent_cron_run WHERE cronId = :cronId")
    long countByCronId(@Bind("cronId") String cronId);

    @SqlQuery("SELECT id, cronId, sessionId, status, startedAt, finishedAt, errorReason, iterationsUsed, inputTokens, outputTokens, usdCost, createdAt FROM agent_cron_run ORDER BY startedAt DESC LIMIT :limit OFFSET :offset")
    List<CronRunDto> findAll(@Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery("SELECT COUNT(*) FROM agent_cron_run")
    long countAll();

    @SqlQuery("SELECT id, cronId, sessionId, status, startedAt, finishedAt, errorReason, iterationsUsed, inputTokens, outputTokens, usdCost, createdAt FROM agent_cron_run WHERE status = :status ORDER BY startedAt DESC LIMIT :limit OFFSET :offset")
    List<CronRunDto> findAllByStatus(@Bind("status") String status, @Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery("SELECT COUNT(*) FROM agent_cron_run WHERE status = :status")
    long countByStatus(@Bind("status") String status);
}
