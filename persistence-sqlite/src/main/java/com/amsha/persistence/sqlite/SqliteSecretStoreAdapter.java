package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SecretStorePort;
import com.amsha.persistence.sqlite.jdbi.SecretJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteSecretStoreAdapter implements SecretStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteSecretStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteSecretStoreAdapter() {
        this(null);
    }

    @Override
    public void save(SecretDto dto) {
        jdbi.useExtension(
                SecretJdbiDao.class,
                dao -> dao.save(
                        dto.sessionId(), dto.keyName(), dto.encryptionKeyVersion(), dto.ciphertext(), dto.nonce()));
    }

    @Override
    public Optional<SecretDto> find(String sessionId, String keyName) {
        return jdbi.withExtension(SecretJdbiDao.class, dao -> dao.find(sessionId, keyName))
                .map(row -> new SecretDto(
                        row.sessionId(), row.keyName(), row.encryptionKeyVersion(), row.ciphertext(), row.nonce()));
    }

    @Override
    public void delete(String sessionId, String keyName) {
        jdbi.useExtension(SecretJdbiDao.class, dao -> dao.delete(sessionId, keyName));
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbi.useExtension(SecretJdbiDao.class, dao -> dao.deleteBySessionId(sessionId));
    }
}
