package com.amsha.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for managing workspace membership invitations.
 */
public interface IdentityInvitationStorePort {
    boolean save(IdentityInvitationDto invitation);

    Optional<IdentityInvitationDto> findById(String invitationId);

    Optional<IdentityInvitationDto> findByTokenDigest(String tokenDigest);

    List<IdentityInvitationDto> listByWorkspaceId(String workspaceId);

    int deleteById(String invitationId);

    int deleteExpired(Instant now);
}
