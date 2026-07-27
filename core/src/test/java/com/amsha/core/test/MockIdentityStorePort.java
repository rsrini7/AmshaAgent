package com.amsha.core.test;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

/**
 * Test-scoped stub for {@link IdentityStorePort} so {@code core} CDI validation succeeds without a
 * persistence adapter on the test classpath. Returns empty/absent by default; tests may subclass or
 * use Mockito to override behavior.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class MockIdentityStorePort implements IdentityStorePort {

    @Override
    public void saveUser(IdentityUserDto user) {}

    @Override
    public Optional<IdentityUserDto> findUserById(String userId) {
        return Optional.empty();
    }

    @Override
    public List<IdentityUserDto> listUsers() {
        return List.of();
    }

    @Override
    public void saveExternalIdentity(ExternalIdentityDto identity) {}

    @Override
    public Optional<ExternalIdentityDto> findExternalIdentity(String providerId, String issuer, String subject) {
        return Optional.empty();
    }

    @Override
    public Optional<ExternalIdentityDto> findExternalIdentityByUserId(String providerId, String userId) {
        return Optional.empty();
    }

    @Override
    public List<ExternalIdentityDto> listExternalIdentitiesByUserId(String userId) {
        return List.of();
    }

    @Override
    public void deleteExternalIdentity(String providerId, String issuer, String subject) {}

    @Override
    public void saveWorkspace(IdentityWorkspaceDto workspace) {}

    @Override
    public Optional<IdentityWorkspaceDto> findWorkspaceById(String workspaceId) {
        return Optional.empty();
    }

    @Override
    public List<IdentityWorkspaceDto> listWorkspaces() {
        return List.of();
    }

    @Override
    public boolean saveMembership(WorkspaceMembershipDto membership) {
        return false;
    }

    @Override
    public Optional<WorkspaceMembershipDto> findMembership(String workspaceId, String userId) {
        return Optional.empty();
    }

    @Override
    public List<WorkspaceMembershipDto> listMembershipsByWorkspaceId(String workspaceId) {
        return List.of();
    }

    @Override
    public List<WorkspaceMembershipDto> listMembershipsByUserId(String userId) {
        return List.of();
    }

    @Override
    public int deleteMembership(String workspaceId, String userId) {
        return 0;
    }

    @Override
    public int deleteMembershipIfNotLastOwner(String workspaceId, String userId) {
        return 0;
    }
}
