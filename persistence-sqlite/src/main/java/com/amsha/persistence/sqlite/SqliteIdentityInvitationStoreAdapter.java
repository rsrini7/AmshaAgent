package com.amsha.persistence.sqlite;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.sqlite.jdbi.IdentityInvitationJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class SqliteIdentityInvitationStoreAdapter implements IdentityInvitationStorePort {

    private final Jdbi jdbi;

    @Inject
    public SqliteIdentityInvitationStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected SqliteIdentityInvitationStoreAdapter() {
        this(null);
    }

    @Override
    public boolean save(IdentityInvitationDto invitation) {
        return jdbi.withExtension(IdentityInvitationJdbiDao.class, dao -> dao.save(invitation)) > 0;
    }

    @Override
    public Optional<IdentityInvitationDto> findById(String invitationId) {
        if (isBlank(invitationId)) return Optional.empty();
        return jdbi.withExtension(IdentityInvitationJdbiDao.class, dao -> dao.findById(invitationId));
    }

    @Override
    public Optional<IdentityInvitationDto> findByTokenDigest(String tokenDigest) {
        if (isBlank(tokenDigest)) return Optional.empty();
        return jdbi.withExtension(IdentityInvitationJdbiDao.class, dao -> dao.findByTokenDigest(tokenDigest));
    }

    @Override
    public List<IdentityInvitationDto> listByWorkspaceId(String workspaceId) {
        if (isBlank(workspaceId)) return List.of();
        return jdbi.withExtension(IdentityInvitationJdbiDao.class, dao -> dao.listByWorkspaceId(workspaceId));
    }

    @Override
    public int deleteById(String invitationId) {
        if (isBlank(invitationId)) return 0;
        return jdbi.withExtension(IdentityInvitationJdbiDao.class, dao -> dao.deleteById(invitationId));
    }

    @Override
    public int deleteExpired(Instant now) {
        if (now == null) return 0;
        return jdbi.withExtension(IdentityInvitationJdbiDao.class, dao -> dao.deleteExpired(now));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
