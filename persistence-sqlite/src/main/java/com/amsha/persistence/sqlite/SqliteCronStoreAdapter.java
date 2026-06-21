package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.state.CronStatus;
import com.amsha.core.util.TemporalUtils;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.sqlite.jdbi.CronJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteCronStoreAdapter implements CronStorePort {

    private static final Logger log = Logger.getLogger(SqliteCronStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public SqliteCronStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteCronStoreAdapter() {
        this(null);
    }

    @Override
    public List<AgentCronDto> findDueCrons() {
        return jdbi.withExtension(
                CronJdbiDao.class,
                dao -> dao.findDueCrons(CronStatus.ACTIVE.name(), TemporalUtils.formatIsoLex(Instant.now())));
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId) {
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findByUserId(userId));
    }

    @Override
    public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.findByUserIdAndStatus(userId, CronStatus.AWAITING_APPROVAL.name()));
    }

    @Override
    public List<AgentCronDto> findAllAwaitingApproval() {
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findAllByStatus(CronStatus.AWAITING_APPROVAL.name()));
    }

    @Override
    public Optional<AgentCronDto> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findById(id));
    }

    @Override
    public boolean claimForFire(String id) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.claimForFire(id, CronStatus.RUNNING.name(), CronStatus.ACTIVE.name()));
    }

    @Override
    public void releaseClaimToActive(String id) {
        if (id == null || id.isBlank()) return;
        jdbi.useExtension(
                CronJdbiDao.class, dao -> dao.releaseClaim(id, CronStatus.ACTIVE.name(), CronStatus.RUNNING.name()));
    }

    @Override
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        if (id == null || id.isBlank()) return;
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markFired(
                        id,
                        CronStatus.ACTIVE.name(),
                        lastFireTime != null ? TemporalUtils.formatIsoLex(lastFireTime) : null,
                        nextFireTime != null ? TemporalUtils.formatIsoLex(nextFireTime) : null,
                        now));
    }

    @Override
    public void markCompleted(String id, Instant lastFireTime) {
        if (id == null || id.isBlank()) return;
        String now = TemporalUtils.formatIsoLex(Instant.now());
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markCompleted(
                        id,
                        CronStatus.COMPLETED.name(),
                        lastFireTime != null ? TemporalUtils.formatIsoLex(lastFireTime) : null,
                        now));
    }

    @Override
    public void markAwaitingApproval(String id) {
        if (id == null || id.isBlank()) return;
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markStatus(
                        id, CronStatus.AWAITING_APPROVAL.name(), TemporalUtils.formatIsoLex(Instant.now())));
    }

    @Override
    public void markRunning(String id) {
        if (id == null || id.isBlank()) return;
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markStatus(id, CronStatus.RUNNING.name(), TemporalUtils.formatIsoLex(Instant.now())));
    }

    @Override
    public void setDeliveryId(String id, String deliveryId) {
        if (id == null || id.isBlank()) return;
        jdbi.useExtension(CronJdbiDao.class, dao -> dao.setDeliveryId(id, deliveryId));
    }

    @Override
    public void markFailed(String id, String errorReason) {
        if (id == null || id.isBlank()) return;
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markFailed(
                        id, CronStatus.FAILED.name(), errorReason, TemporalUtils.formatIsoLex(Instant.now())));
    }

    @Override
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        if (id == null || id.isBlank()) return;
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markSkipped(
                        id,
                        CronStatus.SKIPPED.name(),
                        nextFireTime != null ? TemporalUtils.formatIsoLex(nextFireTime) : null,
                        now != null ? TemporalUtils.formatIsoLex(now) : TemporalUtils.formatIsoLex(Instant.now())));
    }

    @Override
    public void reclaimStaleCrons(Instant threshold) {
        if (threshold == null) return;
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.reclaimStale(
                        CronStatus.ACTIVE.name(), CronStatus.RUNNING.name(), TemporalUtils.formatIsoLex(threshold)));
    }

    @Override
    public void save(AgentCronDto dto) {
        log.tracef("save cron id=%s session=%s", dto.id(), dto.sessionId());
        String now = TemporalUtils.formatIsoLex(Instant.now());
        String createdAt = dto.createdAt() != null ? TemporalUtils.formatIsoLex(dto.createdAt()) : now;
        String nextFire = dto.nextFireTime() != null ? TemporalUtils.formatIsoLex(dto.nextFireTime()) : null;
        String lastFire = dto.lastFireTime() != null ? TemporalUtils.formatIsoLex(dto.lastFireTime()) : null;
        jdbi.useHandle(handle -> handle.createUpdate(
                        "INSERT OR REPLACE INTO agent_cron (id, sessionId, userId, chatId, channelType, channelPlatformId, "
                                + "cronExpression, timezone, runonce, goal, nextFireTime, lastFireTime, status, executionRole, "
                                + "errorReason, lastDeliveryId, createdAt, updatedAt) "
                                + "VALUES (:id, :sessionId, :userId, :chatId, :channelType, :channelPlatformId, "
                                + ":cronExpression, :timezone, :runOnce, :goal, :nextFireTime, :lastFireTime, :status, :executionRole, "
                                + ":errorReason, :lastDeliveryId, :createdAt, :updatedAt)")
                .bind("id", dto.id())
                .bind("sessionId", dto.sessionId())
                .bind("userId", dto.userId())
                .bind("chatId", dto.chatId())
                .bind("channelType", dto.channelType())
                .bind("channelPlatformId", dto.channelPlatformId())
                .bind("cronExpression", dto.cronExpression())
                .bind("timezone", dto.timezone())
                .bind("runOnce", dto.runOnce())
                .bind("goal", dto.goal())
                .bind("nextFireTime", nextFire)
                .bind("lastFireTime", lastFire)
                .bind("status", dto.status())
                .bind("executionRole", dto.executionRole())
                .bind("errorReason", dto.errorReason())
                .bind("lastDeliveryId", dto.lastDeliveryId())
                .bind("createdAt", createdAt)
                .bind("updatedAt", now)
                .execute());
    }

    @Override
    public int deleteByIdAndUserId(String id, String userId) {
        if (id == null || id.isBlank()) return 0;
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.deleteByIdAndUserId(id, userId));
    }

    @Override
    public long deleteAllByUserId(String userId) {
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.deleteAllByUserId(userId));
    }

    @Override
    public long deleteAllBySessionId(String sessionId) {
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.deleteAllBySessionId(sessionId));
    }

    @Override
    public List<AgentCronDto> findAll(String status, int offset, int limit) {
        if (status != null && !status.isBlank()) {
            return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findAllByStatusPaginated(status, offset, limit));
        }
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findAllPaginated(offset, limit));
    }

    @Override
    public long countAll(String status) {
        if (status != null && !status.isBlank()) {
            return jdbi.withExtension(CronJdbiDao.class, dao -> dao.countByStatus(status));
        }
        return jdbi.withExtension(CronJdbiDao.class, CronJdbiDao::countAll);
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId, String status, int offset, int limit) {
        if (status != null && !status.isBlank()) {
            return jdbi.withExtension(
                    CronJdbiDao.class, dao -> dao.findByUserIdAndStatusPaginated(userId, status, offset, limit));
        }
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findByUserIdPaginated(userId, offset, limit));
    }

    @Override
    public long countByUserId(String userId, String status) {
        if (status != null && !status.isBlank()) {
            return jdbi.withExtension(CronJdbiDao.class, dao -> dao.countByUserIdAndStatus(userId, status));
        }
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.countByUserId(userId));
    }
}
