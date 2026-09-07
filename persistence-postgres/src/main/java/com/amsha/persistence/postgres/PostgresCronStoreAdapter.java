package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStatus;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.persistence.postgres.jdbi.CronJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * PostgreSQL implementation of CronStorePort using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresCronStoreAdapter implements CronStorePort {

    private static final Logger log = Logger.getLogger(PostgresCronStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public PostgresCronStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresCronStoreAdapter() {
        this(null);
    }

    @Override
    public List<AgentCronDto> findDueCrons() {
        log.debug("Postgres: Finding due cron tasks...");
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findDueCrons(CronStatus.ACTIVE.name(), Instant.now()));
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId) {
        log.debugf("Postgres: Finding cron tasks for userId=%s", userId);
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findByUserId(userId));
    }

    @Override
    public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
        log.debugf("Postgres: Finding awaiting approval cron tasks for userId=%s", userId);
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.findByUserIdAndStatus(userId, CronStatus.AWAITING_APPROVAL.name()));
    }

    @Override
    public List<AgentCronDto> findAllAwaitingApproval() {
        log.debug("Postgres: Finding all awaiting approval cron tasks...");
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findAllByStatus(CronStatus.AWAITING_APPROVAL.name()));
    }

    @Override
    public Optional<AgentCronDto> findById(String id) {
        log.debugf("Postgres: Finding cron task by id=%s", id);
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.findById(id));
    }

    @Override
    public boolean claimForFire(String id, String deliveryId) {
        log.debugf("Postgres: Claiming cron task %s for fire (deliveryId=%s)...", id, deliveryId);
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class,
                dao -> dao.claimForFire(
                        id, deliveryId, CronStatus.RUNNING.name(), CronStatus.ACTIVE.name(), Instant.now()));
    }

    @Override
    public boolean claimForResume(String id, String deliveryId) {
        log.debugf("Postgres: Claiming cron task %s for resume (deliveryId=%s)...", id, deliveryId);
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.claimForResume(id, deliveryId, Instant.now()) > 0);
    }

    @Override
    public void releaseClaimToActive(String id) {
        log.debugf("Postgres: Releasing cron task %s back to active...", id);
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.releaseClaim(id, CronStatus.ACTIVE.name(), CronStatus.RUNNING.name(), Instant.now()));
    }

    @Override
    public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
        log.debugf("Postgres: Marking cron task %s as fired. last=%s, next=%s", id, lastFireTime, nextFireTime);
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markFired(id, CronStatus.ACTIVE.name(), lastFireTime, nextFireTime, Instant.now()));
    }

    @Override
    public void markCompleted(String id, Instant lastFireTime) {
        log.debugf("Postgres: Marking cron task %s as completed.", id);
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.markCompleted(id, CronStatus.COMPLETED.name(), lastFireTime, Instant.now()));
    }

    @Override
    public void markAwaitingApproval(String id) {
        log.debugf("Postgres: Marking cron task %s as awaiting approval.", id);
        jdbi.useExtension(
                CronJdbiDao.class, dao -> dao.markStatus(id, CronStatus.AWAITING_APPROVAL.name(), Instant.now()));
    }

    @Override
    public void markRunning(String id) {
        log.debugf("Postgres: Marking cron task %s as running.", id);
        jdbi.useExtension(CronJdbiDao.class, dao -> dao.markStatus(id, CronStatus.RUNNING.name(), Instant.now()));
    }

    @Override
    public void setDeliveryId(String id, String deliveryId) {
        log.debugf("Postgres: Setting deliveryId=%s for cron task %s", deliveryId, id);
        jdbi.useExtension(CronJdbiDao.class, dao -> dao.setDeliveryId(id, deliveryId));
    }

    @Override
    public void markFailed(String id, String errorReason) {
        log.debugf("Postgres: Marking cron task %s as failed. reason=%s", id, errorReason);
        jdbi.useExtension(
                CronJdbiDao.class, dao -> dao.markFailed(id, CronStatus.FAILED.name(), errorReason, Instant.now()));
    }

    @Override
    public void markSkipped(String id, Instant nextFireTime, Instant now) {
        log.debugf("Postgres: Marking cron task %s as skipped. next=%s", id, nextFireTime);
        jdbi.useExtension(CronJdbiDao.class, dao -> dao.markSkipped(id, CronStatus.SKIPPED.name(), nextFireTime, now));
    }

    @Override
    public void reclaimStaleCrons(Instant threshold) {
        log.debugf("Postgres: Reclaiming stale cron tasks (threshold=%s)...", threshold);
        jdbi.useExtension(
                CronJdbiDao.class,
                dao -> dao.reclaimStale(CronStatus.ACTIVE.name(), CronStatus.RUNNING.name(), threshold, Instant.now()));
    }

    @Override
    public boolean renewLease(String id, String deliveryId) {
        if (id == null || id.isBlank() || deliveryId == null || deliveryId.isBlank()) return false;
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.renewLease(id, deliveryId, Instant.now()) > 0);
    }

    @Override
    public boolean markCompletedIfOwner(String id, String deliveryId, Instant lastFireTime) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.markCompletedIfOwner(id, deliveryId, lastFireTime, Instant.now()) > 0);
    }

    @Override
    public boolean markFiredIfOwner(String id, String deliveryId, Instant lastFireTime, Instant nextFireTime) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class,
                dao -> dao.markFiredIfOwner(id, deliveryId, lastFireTime, nextFireTime, Instant.now()) > 0);
    }

    @Override
    public boolean markAwaitingApprovalIfOwner(String id, String deliveryId) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.markAwaitingApprovalIfOwner(id, deliveryId, Instant.now()) > 0);
    }

    @Override
    public boolean markAwaitingRetryIfOwner(String id, String deliveryId) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.markAwaitingRetryIfOwner(id, deliveryId, Instant.now()) > 0);
    }

    @Override
    public boolean markFailedIfOwner(String id, String deliveryId, String errorReason) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.markFailedIfOwner(id, deliveryId, errorReason, Instant.now()) > 0);
    }

    @Override
    public boolean markFailedIfStatus(String id, String expectedStatus, String errorReason) {
        if (id == null || id.isBlank()) return false;
        return jdbi.withExtension(
                CronJdbiDao.class, dao -> dao.markFailedIfStatus(id, expectedStatus, errorReason, Instant.now()) > 0);
    }

    @Override
    public void save(AgentCronDto dto) {
        log.debugf("Postgres: Saving cron task %s (goal=%s)", dto.id(), dto.goal());
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CronJdbiDao.class);
            Optional<AgentCronDto> existing = dao.findById(dto.id());
            Instant createdAt = existing.map(AgentCronDto::createdAt).orElseGet(Instant::now);
            AgentCronDto toSave = new AgentCronDto(
                    dto.id(),
                    dto.sessionId(),
                    dto.userId(),
                    dto.chatId(),
                    dto.channelType(),
                    dto.channelPlatformId(),
                    dto.cronExpression(),
                    dto.timezone(),
                    dto.runOnce(),
                    dto.goal(),
                    dto.nextFireTime(),
                    dto.lastFireTime(),
                    dto.status(),
                    dto.executionRole(),
                    dto.errorReason(),
                    dto.lastDeliveryId(),
                    createdAt,
                    Instant.now());
            dao.save(toSave);
        });
    }

    @Override
    public int deleteByIdAndUserId(String id, String userId) {
        log.debugf("Postgres: Deleting cron task %s for userId=%s", id, userId);
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.deleteByIdAndUserId(id, userId));
    }

    @Override
    public long deleteAllByUserId(String userId) {
        log.debugf("Postgres: Deleting all cron tasks for userId=%s", userId);
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.deleteAllByUserId(userId));
    }

    @Override
    public long deleteAllBySessionId(String sessionId) {
        log.debugf("Postgres: Deleting all cron tasks for sessionId=%s", sessionId);
        return jdbi.withExtension(CronJdbiDao.class, dao -> dao.deleteAllBySessionId(sessionId));
    }

    @Override
    public List<AgentCronDto> findAll(String status, int offset, int limit) {
        log.debugf("Postgres: Finding all cron tasks (status=%s, offset=%d, limit=%d)", status, offset, limit);
        return jdbi.withExtension(CronJdbiDao.class, dao -> {
            if (status != null && !status.isBlank()) {
                return dao.findAllByStatusPaginated(status, offset, limit);
            }
            return dao.findAllPaginated(offset, limit);
        });
    }

    @Override
    public long countAll(String status) {
        log.debugf("Postgres: Counting all cron tasks (status=%s)", status);
        return jdbi.withExtension(CronJdbiDao.class, dao -> {
            if (status != null && !status.isBlank()) {
                return dao.countByStatus(status);
            }
            return dao.countAll();
        });
    }

    @Override
    public List<AgentCronDto> findByUserId(String userId, String status, int offset, int limit) {
        log.debugf(
                "Postgres: Finding cron tasks for userId=%s (status=%s, offset=%d, limit=%d)",
                userId, status, offset, limit);
        return jdbi.withExtension(CronJdbiDao.class, dao -> {
            if (status != null && !status.isBlank()) {
                return dao.findByUserIdAndStatusPaginated(userId, status, offset, limit);
            }
            return dao.findByUserIdPaginated(userId, offset, limit);
        });
    }

    @Override
    public long countByUserId(String userId, String status) {
        log.debugf("Postgres: Counting cron tasks for userId=%s (status=%s)", userId, status);
        return jdbi.withExtension(CronJdbiDao.class, dao -> {
            if (status != null && !status.isBlank()) {
                return dao.countByUserIdAndStatus(userId, status);
            }
            return dao.countByUserId(userId);
        });
    }
}
