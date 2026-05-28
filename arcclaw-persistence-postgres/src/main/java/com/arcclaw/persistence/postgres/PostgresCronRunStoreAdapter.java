package com.arcclaw.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.CronRunStorePort;
import com.arcclaw.persistence.postgres.jdbi.CronRunJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresCronRunStoreAdapter implements CronRunStorePort {

    private static final Logger log = Logger.getLogger(PostgresCronRunStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public PostgresCronRunStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresCronRunStoreAdapter() {
        this(null);
    }

    @Override
    public void save(CronRunDto dto) {
        log.debugf("save: dto=%s", dto);
        jdbi.useTransaction(handle -> {
            var dao = handle.attach(CronRunJdbiDao.class);
            Optional<CronRunDto> existing = dao.findById(dto.id());
            Instant createdAt = existing.map(CronRunDto::createdAt).orElseGet(Instant::now);
            CronRunDto toSave = new CronRunDto(
                    dto.id(),
                    dto.cronId(),
                    dto.sessionId(),
                    dto.status(),
                    dto.startedAt(),
                    dto.finishedAt(),
                    dto.errorReason(),
                    dto.iterationsUsed(),
                    dto.inputTokens(),
                    dto.outputTokens(),
                    dto.usdCost(),
                    createdAt);
            dao.save(toSave);
        });
    }

    @Override
    public Optional<CronRunDto> findById(String id) {
        log.debugf("findById: id=%s", id);
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbi.withExtension(CronRunJdbiDao.class, dao -> dao.findById(id));
    }

    @Override
    public List<CronRunDto> findByCronId(String cronId, int offset, int limit) {
        log.debugf("findByCronId: cronId=%s, offset=%d, limit=%d", cronId, offset, limit);
        return jdbi.withExtension(CronRunJdbiDao.class, dao -> dao.findByCronId(cronId, offset, limit));
    }

    @Override
    public long countByCronId(String cronId) {
        log.debugf("countByCronId: cronId=%s", cronId);
        return jdbi.withExtension(CronRunJdbiDao.class, dao -> dao.countByCronId(cronId));
    }

    @Override
    public List<CronRunDto> findAll(String status, int offset, int limit) {
        log.debugf("findAll: status=%s, offset=%d, limit=%d", status, offset, limit);
        if (status != null && !status.isBlank()) {
            return jdbi.withExtension(CronRunJdbiDao.class, dao -> dao.findAllByStatus(status, offset, limit));
        }
        return jdbi.withExtension(CronRunJdbiDao.class, dao -> dao.findAll(offset, limit));
    }

    @Override
    public long countAll(String status) {
        log.debugf("countAll: status=%s", status);
        if (status != null && !status.isBlank()) {
            return jdbi.withExtension(CronRunJdbiDao.class, dao -> dao.countByStatus(status));
        }
        return jdbi.withExtension(CronRunJdbiDao.class, CronRunJdbiDao::countAll);
    }
}
