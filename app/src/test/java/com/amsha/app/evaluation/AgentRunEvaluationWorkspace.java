package com.amsha.app.evaluation;

import java.time.Instant;

import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.IdentityWorkspaceDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

/** Seeds the selected workspace required by durable tool-attempt admission in REST integration tests. */
final class AgentRunEvaluationWorkspace {

    static final String ID = "agent-run-evaluation-workspace";
    static final String USER_ID = "internal";

    private AgentRunEvaluationWorkspace() {}

    static void ensureExists(IdentityStorePort identityStore) {
        Instant now = Instant.now();
        if (identityStore.findUserById(USER_ID).isEmpty()) {
            identityStore.saveUser(new IdentityUserDto(USER_ID, USER_ID, "ACTIVE", now, now));
        }
        if (identityStore.findWorkspaceById(ID).isEmpty()) {
            identityStore.saveWorkspace(new IdentityWorkspaceDto(ID, ID, USER_ID, now, now));
        }
        if (identityStore.findMembership(ID, USER_ID).isEmpty()) {
            identityStore.saveMembership(new WorkspaceMembershipDto(ID, USER_ID, "OWNER", "ACTIVE", 1, now, now));
        }
    }
}
