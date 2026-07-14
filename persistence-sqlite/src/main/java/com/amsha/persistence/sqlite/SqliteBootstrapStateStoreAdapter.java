package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.sqlite.jdbi.BootstrapStateJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteBootstrapStateStoreAdapter implements BootstrapStateStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteBootstrapStateStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteBootstrapStateStoreAdapter() {
        this(null);
    }

    @Override
    public void save(BootstrapStateDto state) {
        jdbi.useExtension(BootstrapStateJdbiDao.class, dao -> dao.save(state));
    }

    @Override
    public boolean claimFirstOwner(String ownerId, String credentialDigest, Instant issuedAt) {
        return jdbi.withExtension(
                        BootstrapStateJdbiDao.class, dao -> dao.claimFirstOwner(ownerId, credentialDigest, issuedAt))
                > 0;
    }

    @Override
    public boolean confirmClaim(int expectedRevision, Instant claimedAt, Instant updatedAt) {
        return jdbi.withExtension(
                        BootstrapStateJdbiDao.class, dao -> dao.confirmClaim(expectedRevision, claimedAt, updatedAt))
                > 0;
    }

    @Override
    public boolean replaceCredential(int expectedRevision, String newDigest, Instant issuedAt, Instant updatedAt) {
        return jdbi.withExtension(
                        BootstrapStateJdbiDao.class,
                        dao -> dao.replaceCredential(expectedRevision, newDigest, issuedAt, updatedAt))
                > 0;
    }

    @Override
    public Optional<BootstrapStateDto> getState() {
        return jdbi.withExtension(BootstrapStateJdbiDao.class, BootstrapStateJdbiDao::getState);
    }
}
