package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.sqlite.jdbi.BootstrapStateJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.SQLITE, enableIfMissing = true)
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
    public Optional<Integer> reserveFirstOwner(String ownerId, String workspaceId, Instant reservedAt) {
        int affected = jdbi.withExtension(
                BootstrapStateJdbiDao.class, dao -> dao.reserveFirstOwner(ownerId, workspaceId, reservedAt));
        if (affected <= 0) {
            return Optional.empty();
        }
        return getState().map(BootstrapStateDto::revision);
    }

    @Override
    public boolean recordCredentialIssued(int expectedRevision, String credentialDigest, Instant issuedAt) {
        return jdbi.withExtension(
                        BootstrapStateJdbiDao.class,
                        dao -> dao.recordCredentialIssued(expectedRevision, credentialDigest, issuedAt))
                > 0;
    }

    @Override
    public boolean confirmClaim(int expectedRevision, String authenticatedOwnerId, Instant claimedAt) {
        return jdbi.withExtension(
                        BootstrapStateJdbiDao.class,
                        dao -> dao.confirmClaim(expectedRevision, authenticatedOwnerId, claimedAt))
                > 0;
    }

    @Override
    public boolean rollbackClaiming(int expectedRevision, Instant rolledBackAt) {
        return jdbi.withExtension(
                        BootstrapStateJdbiDao.class, dao -> dao.rollbackClaiming(expectedRevision, rolledBackAt))
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
