package com.amsha.persistence.fs;

import java.util.List;
import java.util.Objects;

import com.amsha.persistence.api.ToolActionClaimDto;

/**
 * Versioned filesystem envelope holding AT_MOST_ONCE claims for a workspace per Proposal §12.4.
 */
public record FsWorkspaceClaimsEnvelope(int version, String workspaceId, List<ToolActionClaimDto> claims) {

    public FsWorkspaceClaimsEnvelope {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        claims = claims != null ? List.copyOf(claims) : List.of();
    }

    public static FsWorkspaceClaimsEnvelope empty(String workspaceId) {
        return new FsWorkspaceClaimsEnvelope(1, workspaceId, List.of());
    }
}
