package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsIdentityStoreAdapter implements IdentityStorePort {

    private static final String DOMAIN = "identity";
    private static final String USERS = "users";
    private static final String EXTERNAL_IDENTITIES = "external-identities";
    private static final String WORKSPACES = "workspaces";
    private static final String MEMBERSHIPS = "memberships";

    @Inject
    FilesystemFileStore store;

    @Override
    public void saveUser(IdentityUserDto user) {
        store.write(List.of(DOMAIN, USERS, user.userId() + ".json"), user);
    }

    @Override
    public Optional<IdentityUserDto> findUserById(String userId) {
        return store.readOptional(List.of(DOMAIN, USERS, userId + ".json"), IdentityUserDto.class);
    }

    @Override
    public List<IdentityUserDto> listUsers() {
        return store.list(List.of(DOMAIN, USERS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, USERS, file), IdentityUserDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public synchronized void saveExternalIdentity(ExternalIdentityDto identity) {
        String key = identity.providerId() + "_" + identity.issuer() + "_" + identity.subject();
        List<String> path = List.of(DOMAIN, EXTERNAL_IDENTITIES, key + ".json");
        Optional<ExternalIdentityDto> existing = store.readOptional(path, ExternalIdentityDto.class);
        if (existing.isEmpty() || existing.get().userId().equals(identity.userId())) {
            store.write(path, identity);
        }
    }

    @Override
    public Optional<ExternalIdentityDto> findExternalIdentity(String providerId, String issuer, String subject) {
        String key = providerId + "_" + issuer + "_" + subject;
        return store.readOptional(List.of(DOMAIN, EXTERNAL_IDENTITIES, key + ".json"), ExternalIdentityDto.class);
    }

    @Override
    public Optional<ExternalIdentityDto> findExternalIdentityByUserId(String providerId, String userId) {
        return listExternalIdentitiesByUserId(userId).stream()
                .filter(identity -> providerId.equals(identity.providerId()))
                .findFirst();
    }

    @Override
    public List<ExternalIdentityDto> listExternalIdentitiesByUserId(String userId) {
        return store.list(List.of(DOMAIN, EXTERNAL_IDENTITIES), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, EXTERNAL_IDENTITIES, file), ExternalIdentityDto.class))
                .flatMap(Optional::stream)
                .filter(identity -> userId.equals(identity.userId()))
                .toList();
    }

    @Override
    public void deleteExternalIdentity(String providerId, String issuer, String subject) {
        String key = providerId + "_" + issuer + "_" + subject;
        store.delete(List.of(DOMAIN, EXTERNAL_IDENTITIES, key + ".json"));
    }

    @Override
    public void saveWorkspace(IdentityWorkspaceDto workspace) {
        store.write(List.of(DOMAIN, WORKSPACES, workspace.workspaceId() + ".json"), workspace);
    }

    @Override
    public Optional<IdentityWorkspaceDto> findWorkspaceById(String workspaceId) {
        return store.readOptional(List.of(DOMAIN, WORKSPACES, workspaceId + ".json"), IdentityWorkspaceDto.class);
    }

    @Override
    public List<IdentityWorkspaceDto> listWorkspaces() {
        return store.list(List.of(DOMAIN, WORKSPACES), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, WORKSPACES, file), IdentityWorkspaceDto.class))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public boolean saveMembership(WorkspaceMembershipDto membership) {
        String key = membership.workspaceId() + "_" + membership.userId();
        List<String> path = List.of(DOMAIN, MEMBERSHIPS, key + ".json");
        Optional<WorkspaceMembershipDto> existing = store.readOptional(path, WorkspaceMembershipDto.class);
        if (existing.isEmpty()) {
            store.write(path, membership);
            return true;
        }
        if (existing.get().revision() != membership.revision()) {
            return false;
        }
        WorkspaceMembershipDto toWrite = new WorkspaceMembershipDto(
                membership.workspaceId(),
                membership.userId(),
                membership.role(),
                membership.status(),
                membership.revision() + 1,
                membership.createdAt(),
                membership.updatedAt());
        store.write(path, toWrite);
        return true;
    }

    @Override
    public Optional<WorkspaceMembershipDto> findMembership(String workspaceId, String userId) {
        String key = workspaceId + "_" + userId;
        return store.readOptional(List.of(DOMAIN, MEMBERSHIPS, key + ".json"), WorkspaceMembershipDto.class);
    }

    @Override
    public List<WorkspaceMembershipDto> listMembershipsByWorkspaceId(String workspaceId) {
        return store.list(List.of(DOMAIN, MEMBERSHIPS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, MEMBERSHIPS, file), WorkspaceMembershipDto.class))
                .flatMap(Optional::stream)
                .filter(m -> workspaceId.equals(m.workspaceId()))
                .toList();
    }

    @Override
    public List<WorkspaceMembershipDto> listMembershipsByUserId(String userId) {
        return store.list(List.of(DOMAIN, MEMBERSHIPS), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, MEMBERSHIPS, file), WorkspaceMembershipDto.class))
                .flatMap(Optional::stream)
                .filter(m -> userId.equals(m.userId()))
                .toList();
    }

    @Override
    public int deleteMembership(String workspaceId, String userId) {
        String key = workspaceId + "_" + userId;
        boolean deleted = store.delete(List.of(DOMAIN, MEMBERSHIPS, key + ".json"));
        return deleted ? 1 : 0;
    }

    @Override
    public synchronized int deleteMembershipIfNotLastOwner(String workspaceId, String userId) {
        Optional<WorkspaceMembershipDto> target = findMembership(workspaceId, userId);
        if (target.isEmpty()) return 0;
        if (AccessRoles.OWNER.equalsIgnoreCase(target.get().role())) {
            long activeOwners = listMembershipsByWorkspaceId(workspaceId).stream()
                    .filter(m -> AccessRoles.OWNER.equalsIgnoreCase(m.role()) && "ACTIVE".equalsIgnoreCase(m.status()))
                    .count();
            if (activeOwners <= 1) return 0;
        }
        return deleteMembership(workspaceId, userId);
    }
}
