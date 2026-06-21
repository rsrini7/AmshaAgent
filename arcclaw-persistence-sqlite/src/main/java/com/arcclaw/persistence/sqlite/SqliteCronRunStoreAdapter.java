package com.arcclaw.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.persistence.api.CronRunDto;
import com.arcclaw.persistence.api.CronRunStorePort;
import com.arcclaw.persistence.sqlite.jdbi.CronRunJdbiDao;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteCronRunStoreAdapter implements CronRunStorePort {

    private static final Logger log = Logger.getLogger(SqliteCronRunStoreAdapter.class);

    private final Jdbi jdbi;

    @Inject
    public SqliteCronRunStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteCronRunStoreAdapter() {
        this(null);
    }

    @Override
    public void save(CronRunDto dto) {
        log.debugf("save: dto=%s", dto);
        jdbi.useExtension(CronRunJdbiDao.class, dao -> dao.save(dto));
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
