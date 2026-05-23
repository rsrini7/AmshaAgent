package com.arcclaw.persistence.postgres;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.SecretDto;
import com.arcclaw.persistence.api.SecretStorePort;
import com.arcclaw.persistence.postgres.jdbi.SecretJdbiDao;
import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jdbi.v3.core.Jdbi;

/**
 * PostgreSQL implementation of SecretStorePort using JDBI.
 */
@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "postgres")
public class PostgresSecretStoreAdapter implements SecretStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresSecretStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresSecretStoreAdapter() {
        this(null);
    }

    @Override
    public void save(SecretDto dto) {
        jdbi.useExtension(SecretJdbiDao.class, dao -> dao.save(
            dto.sessionId(),
            dto.keyName(),
            dto.encryptionKeyVersion(),
            dto.ciphertext(),
            dto.nonce()
        ));
    }

    @Override
    public Optional<SecretDto> find(String sessionId, String keyName) {
        return jdbi.withExtension(SecretJdbiDao.class, dao -> dao.find(sessionId, keyName))
            .map(row -> new SecretDto(
                row.sessionId(),
                row.keyName(),
                row.encryptionKeyVersion(),
                row.ciphertext(),
                row.nonce()
            ));
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
