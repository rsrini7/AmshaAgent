package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for managing user identities, external OIDC/OAuth credentials,
 * workspaces, and workspace memberships.
 */
public interface IdentityStorePort {

    // User operations
    void saveUser(IdentityUserDto user);

    Optional<IdentityUserDto> findUserById(String userId);

    List<IdentityUserDto> listUsers();

    // External Identity operations
    void saveExternalIdentity(ExternalIdentityDto identity);

    Optional<ExternalIdentityDto> findExternalIdentity(String providerId, String issuer, String subject);

    Optional<ExternalIdentityDto> findExternalIdentityByUserId(String providerId, String userId);

    List<ExternalIdentityDto> listExternalIdentitiesByUserId(String userId);

    void deleteExternalIdentity(String providerId, String issuer, String subject);

    // Workspace operations
    void saveWorkspace(IdentityWorkspaceDto workspace);

    Optional<IdentityWorkspaceDto> findWorkspaceById(String workspaceId);

    List<IdentityWorkspaceDto> listWorkspaces();

    // Workspace Membership operations
    boolean saveMembership(WorkspaceMembershipDto membership);

    Optional<WorkspaceMembershipDto> findMembership(String workspaceId, String userId);

    List<WorkspaceMembershipDto> listMembershipsByWorkspaceId(String workspaceId);

    List<WorkspaceMembershipDto> listMembershipsByUserId(String userId);

    int deleteMembership(String workspaceId, String userId);

    int deleteMembershipIfNotLastOwner(String workspaceId, String userId);
}
