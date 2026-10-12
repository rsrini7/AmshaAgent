package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsIdentityInvitationStoreAdapter implements IdentityInvitationStorePort {

    private static final String DOMAIN = "identity-invitations";

    @Inject
    FilesystemFileStore store;

    @Override
    public synchronized boolean save(IdentityInvitationDto invitation) {
        List<String> path = List.of(DOMAIN, invitation.invitationId() + ".json");
        Optional<IdentityInvitationDto> existing = store.readOptional(path, IdentityInvitationDto.class);
        if (existing.isEmpty()) {
            store.write(path, invitation);
            return true;
        }
        if (existing.get().revision() != invitation.revision()) {
            return false;
        }
        IdentityInvitationDto toWrite = new IdentityInvitationDto(
                invitation.invitationId(),
                invitation.workspaceId(),
                invitation.role(),
                invitation.tokenDigest(),
                invitation.optionalEmailConstraint(),
                invitation.expiresAt(),
                invitation.acceptedBy(),
                invitation.revokedAt(),
                invitation.revision() + 1,
                invitation.createdAt(),
                invitation.updatedAt());
        store.write(path, toWrite);
        return true;
    }

    @Override
    public Optional<IdentityInvitationDto> findById(String invitationId) {
        return store.readOptional(List.of(DOMAIN, invitationId + ".json"), IdentityInvitationDto.class);
    }

    @Override
    public Optional<IdentityInvitationDto> findByTokenDigest(String tokenDigest) {
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), IdentityInvitationDto.class))
                .flatMap(Optional::stream)
                .filter(inv -> tokenDigest.equals(inv.tokenDigest()))
                .findFirst();
    }

    @Override
    public List<IdentityInvitationDto> listByWorkspaceId(String workspaceId) {
        return store.list(List.of(DOMAIN), ".json").stream()
                .map(file -> store.readOptional(List.of(DOMAIN, file), IdentityInvitationDto.class))
                .flatMap(Optional::stream)
                .filter(inv -> workspaceId.equals(inv.workspaceId()))
                .toList();
    }

    @Override
    public int deleteById(String invitationId) {
        boolean deleted = store.delete(List.of(DOMAIN, invitationId + ".json"));
        return deleted ? 1 : 0;
    }

    @Override
    public int deleteExpired(Instant now) {
        List<String> files = store.list(List.of(DOMAIN), ".json");
        int count = 0;
        for (String file : files) {
            Optional<IdentityInvitationDto> invitation =
                    store.readOptional(List.of(DOMAIN, file), IdentityInvitationDto.class);
            if (invitation.isPresent()) {
                IdentityInvitationDto inv = invitation.get();
                if (inv.expiresAt().isBefore(now)) {
                    if (store.delete(List.of(DOMAIN, file))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
