package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.amsha.persistence.postgres.jdbi.IdentityJdbiDao;

import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
public class PostgresIdentityStoreAdapter implements IdentityStorePort {

    private final Jdbi jdbi;

    @Inject
    public PostgresIdentityStoreAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresIdentityStoreAdapter() {
        this(null);
    }

    @Override
    public void saveUser(IdentityUserDto user) {
        jdbi.useExtension(IdentityJdbiDao.class, dao -> dao.saveUser(user));
    }

    @Override
    public Optional<IdentityUserDto> findUserById(String userId) {
        if (isBlank(userId)) return Optional.empty();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.findUserById(userId));
    }

    @Override
    public List<IdentityUserDto> listUsers() {
        return jdbi.withExtension(IdentityJdbiDao.class, IdentityJdbiDao::listUsers);
    }

    @Override
    public void saveExternalIdentity(ExternalIdentityDto identity) {
        jdbi.useExtension(IdentityJdbiDao.class, dao -> dao.saveExternalIdentity(identity));
    }

    @Override
    public Optional<ExternalIdentityDto> findExternalIdentity(String providerId, String issuer, String subject) {
        if (isBlank(providerId) || isBlank(issuer) || isBlank(subject)) return Optional.empty();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.findExternalIdentity(providerId, issuer, subject));
    }

    @Override
    public Optional<ExternalIdentityDto> findExternalIdentityByUserId(String providerId, String userId) {
        if (isBlank(providerId) || isBlank(userId)) return Optional.empty();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.findExternalIdentityByUserId(providerId, userId));
    }

    @Override
    public List<ExternalIdentityDto> listExternalIdentitiesByUserId(String userId) {
        if (isBlank(userId)) return List.of();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.listExternalIdentitiesByUserId(userId));
    }

    @Override
    public void deleteExternalIdentity(String providerId, String issuer, String subject) {
        if (isBlank(providerId) || isBlank(issuer) || isBlank(subject)) return;
        jdbi.useExtension(IdentityJdbiDao.class, dao -> dao.deleteExternalIdentity(providerId, issuer, subject));
    }

    @Override
    public void saveWorkspace(IdentityWorkspaceDto workspace) {
        jdbi.useExtension(IdentityJdbiDao.class, dao -> dao.saveWorkspace(workspace));
    }

    @Override
    public Optional<IdentityWorkspaceDto> findWorkspaceById(String workspaceId) {
        if (isBlank(workspaceId)) return Optional.empty();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.findWorkspaceById(workspaceId));
    }

    @Override
    public List<IdentityWorkspaceDto> listWorkspaces() {
        return jdbi.withExtension(IdentityJdbiDao.class, IdentityJdbiDao::listWorkspaces);
    }

    @Override
    public boolean saveMembership(WorkspaceMembershipDto membership) {
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.saveMembership(membership)) > 0;
    }

    @Override
    public Optional<WorkspaceMembershipDto> findMembership(String workspaceId, String userId) {
        if (isBlank(workspaceId) || isBlank(userId)) return Optional.empty();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.findMembership(workspaceId, userId));
    }

    @Override
    public List<WorkspaceMembershipDto> listMembershipsByWorkspaceId(String workspaceId) {
        if (isBlank(workspaceId)) return List.of();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.listMembershipsByWorkspaceId(workspaceId));
    }

    @Override
    public List<WorkspaceMembershipDto> listMembershipsByUserId(String userId) {
        if (isBlank(userId)) return List.of();
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.listMembershipsByUserId(userId));
    }

    @Override
    public int deleteMembership(String workspaceId, String userId) {
        if (isBlank(workspaceId) || isBlank(userId)) return 0;
        return jdbi.withExtension(IdentityJdbiDao.class, dao -> dao.deleteMembership(workspaceId, userId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
