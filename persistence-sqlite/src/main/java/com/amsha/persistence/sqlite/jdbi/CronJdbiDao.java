package com.amsha.persistence.sqlite.jdbi;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.AgentCronDto;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(AgentCronDto.class)
public interface CronJdbiDao {

    String CRON_COLUMNS =
            "id, sessionId, userId, chatId, channelType, channelPlatformId, cronExpression, timezone, runonce AS \"runOnce\", goal, nextFireTime, lastFireTime, status, executionRole, errorReason, lastDeliveryId, createdAt, updatedAt";

    @SqlQuery("SELECT " + CRON_COLUMNS + " FROM agent_cron WHERE status = :status AND nextFireTime <= :now")
    List<AgentCronDto> findDueCrons(@Bind("status") String status, @Bind("now") String now);

    @SqlQuery("SELECT " + CRON_COLUMNS + " FROM agent_cron WHERE userId = :userId ORDER BY nextFireTime ASC")
    List<AgentCronDto> findByUserId(@Bind("userId") String userId);

    @SqlQuery("SELECT " + CRON_COLUMNS
            + " FROM agent_cron WHERE userId = :userId AND status = :status ORDER BY nextFireTime ASC")
    List<AgentCronDto> findByUserIdAndStatus(@Bind("userId") String userId, @Bind("status") String status);

    @SqlQuery("SELECT " + CRON_COLUMNS + " FROM agent_cron WHERE status = :status ORDER BY nextFireTime ASC")
    List<AgentCronDto> findAllByStatus(@Bind("status") String status);

    @SqlQuery("SELECT " + CRON_COLUMNS + " FROM agent_cron ORDER BY nextFireTime ASC LIMIT :limit OFFSET :offset")
    List<AgentCronDto> findAllPaginated(@Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery("SELECT " + CRON_COLUMNS
            + " FROM agent_cron WHERE status = :status ORDER BY nextFireTime ASC LIMIT :limit OFFSET :offset")
    List<AgentCronDto> findAllByStatusPaginated(
            @Bind("status") String status, @Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery("SELECT " + CRON_COLUMNS
            + " FROM agent_cron WHERE userId = :userId ORDER BY nextFireTime ASC LIMIT :limit OFFSET :offset")
    List<AgentCronDto> findByUserIdPaginated(
            @Bind("userId") String userId, @Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery(
            "SELECT " + CRON_COLUMNS
                    + " FROM agent_cron WHERE userId = :userId AND status = :status ORDER BY nextFireTime ASC LIMIT :limit OFFSET :offset")
    List<AgentCronDto> findByUserIdAndStatusPaginated(
            @Bind("userId") String userId,
            @Bind("status") String status,
            @Bind("offset") int offset,
            @Bind("limit") int limit);

    @SqlQuery("SELECT COUNT(*) FROM agent_cron")
    long countAll();

    @SqlQuery("SELECT COUNT(*) FROM agent_cron WHERE status = :status")
    long countByStatus(@Bind("status") String status);

    @SqlQuery("SELECT COUNT(*) FROM agent_cron WHERE userId = :userId")
    long countByUserId(@Bind("userId") String userId);

    @SqlQuery("SELECT COUNT(*) FROM agent_cron WHERE userId = :userId AND status = :status")
    long countByUserIdAndStatus(@Bind("userId") String userId, @Bind("status") String status);

    @SqlQuery("SELECT " + CRON_COLUMNS + " FROM agent_cron WHERE id = :id")
    Optional<AgentCronDto> findById(@Bind("id") String id);

    @SqlUpdate("UPDATE agent_cron SET status = :status WHERE id = :id AND status = :expectedStatus")
    boolean claimForFire(
            @Bind("id") String id, @Bind("status") String status, @Bind("expectedStatus") String expectedStatus);

    @SqlUpdate("UPDATE agent_cron SET status = :status WHERE id = :id AND status = :expectedStatus")
    boolean releaseClaim(
            @Bind("id") String id, @Bind("status") String status, @Bind("expectedStatus") String expectedStatus);

    @SqlUpdate(
            "UPDATE agent_cron SET status = :status, lastFireTime = :lastFire, nextFireTime = :nextFire, updatedAt = :now WHERE id = :id")
    void markFired(
            @Bind("id") String id,
            @Bind("status") String status,
            @Bind("lastFire") String lastFire,
            @Bind("nextFire") String nextFire,
            @Bind("now") String now);

    @SqlUpdate("UPDATE agent_cron SET status = :status, lastFireTime = :lastFire, updatedAt = :now WHERE id = :id")
    void markCompleted(
            @Bind("id") String id,
            @Bind("status") String status,
            @Bind("lastFire") String lastFire,
            @Bind("now") String now);

    @SqlUpdate("UPDATE agent_cron SET status = :status, updatedAt = :now WHERE id = :id")
    void markStatus(@Bind("id") String id, @Bind("status") String status, @Bind("now") String now);

    @SqlUpdate("UPDATE agent_cron SET status = :status, errorReason = :reason, updatedAt = :now WHERE id = :id")
    void markFailed(
            @Bind("id") String id,
            @Bind("status") String status,
            @Bind("reason") String reason,
            @Bind("now") String now);

    @SqlUpdate("UPDATE agent_cron SET status = :status, nextFireTime = :nextFire, updatedAt = :now WHERE id = :id")
    void markSkipped(
            @Bind("id") String id,
            @Bind("status") String status,
            @Bind("nextFire") String nextFire,
            @Bind("now") String now);

    @SqlUpdate("UPDATE agent_cron SET lastDeliveryId = :deliveryId WHERE id = :id")
    void setDeliveryId(@Bind("id") String id, @Bind("deliveryId") String deliveryId);

    @SqlUpdate(
            "UPDATE agent_cron SET status = :status WHERE status = :running AND (lastFireTime <= :threshold OR (lastFireTime IS NULL AND createdAt <= :threshold))")
    int reclaimStale(
            @Bind("status") String status, @Bind("running") String running, @Bind("threshold") String threshold);

    @SqlUpdate(
            "INSERT OR REPLACE INTO agent_cron (id, sessionId, userId, chatId, channelType, channelPlatformId, cronExpression, timezone, runonce, goal, nextFireTime, lastFireTime, status, executionRole, errorReason, lastDeliveryId, createdAt, updatedAt) VALUES (:id, :sessionId, :userId, :chatId, :channelType, :channelPlatformId, :cronExpression, :timezone, :runOnce, :goal, :nextFireTime, :lastFireTime, :status, :executionRole, :errorReason, :lastDeliveryId, :createdAt, :updatedAt)")
    void save(@BindMethods AgentCronDto dto);

    @SqlUpdate("DELETE FROM agent_cron WHERE id = :id AND userId = :userId")
    int deleteByIdAndUserId(@Bind("id") String id, @Bind("userId") String userId);

    @SqlUpdate("DELETE FROM agent_cron WHERE userId = :userId")
    long deleteAllByUserId(@Bind("userId") String userId);

    @SqlUpdate("DELETE FROM agent_cron WHERE sessionId = :sessionId")
    long deleteAllBySessionId(@Bind("sessionId") String sessionId);
}
