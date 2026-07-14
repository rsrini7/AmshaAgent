package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.postgres.jdbi.WebSessionJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresWebSessionStoreAdapter implements WebSessionStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresWebSessionStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresWebSessionStoreAdapter() {
        this(null);
    }

    @Override
    public boolean save(WebSessionDto session) {
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.save(session)) > 0;
    }

    @Override
    public Optional<WebSessionDto> findById(String sessionId) {
        if (isBlank(sessionId)) return Optional.empty();
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.findById(sessionId));
    }

    @Override
    public Optional<WebSessionDto> findByTokenDigest(String tokenDigest) {
        if (isBlank(tokenDigest)) return Optional.empty();
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.findByTokenDigest(tokenDigest));
    }

    @Override
    public List<WebSessionDto> listByUserId(String userId) {
        if (isBlank(userId)) return List.of();
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.listByUserId(userId));
    }

    @Override
    public int deleteById(String sessionId) {
        if (isBlank(sessionId)) return 0;
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.deleteById(sessionId));
    }

    @Override
    public int deleteByUserId(String userId) {
        if (isBlank(userId)) return 0;
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.deleteByUserId(userId));
    }

    @Override
    public int deleteExpired(Instant now) {
        if (now == null) return 0;
        return jdbi.withExtension(WebSessionJdbiDao.class, dao -> dao.deleteExpired(now));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
