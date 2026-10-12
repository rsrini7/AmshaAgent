package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.postgres.jdbi.ApiCredentialJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
public class PostgresApiCredentialStoreAdapter implements ApiCredentialStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresApiCredentialStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresApiCredentialStoreAdapter() {
        this(null);
    }

    @Override
    public boolean save(ApiCredentialDto credential) {
        return jdbi.withExtension(ApiCredentialJdbiDao.class, dao -> dao.save(credential)) > 0;
    }

    @Override
    public Optional<ApiCredentialDto> findById(String credentialId) {
        if (isBlank(credentialId)) return Optional.empty();
        return jdbi.withExtension(ApiCredentialJdbiDao.class, dao -> dao.findById(credentialId));
    }

    @Override
    public Optional<ApiCredentialDto> findByKeyDigest(String keyDigest) {
        if (isBlank(keyDigest)) return Optional.empty();
        return jdbi.withExtension(ApiCredentialJdbiDao.class, dao -> dao.findByKeyDigest(keyDigest));
    }

    @Override
    public List<ApiCredentialDto> listByUserId(String userId) {
        if (isBlank(userId)) return List.of();
        return jdbi.withExtension(ApiCredentialJdbiDao.class, dao -> dao.listByUserId(userId));
    }

    @Override
    public int deleteById(String credentialId) {
        if (isBlank(credentialId)) return 0;
        return jdbi.withExtension(ApiCredentialJdbiDao.class, dao -> dao.deleteById(credentialId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
