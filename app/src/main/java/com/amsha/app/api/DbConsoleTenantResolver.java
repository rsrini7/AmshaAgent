package com.amsha.app.api;

import jakarta.enterprise.inject.Instance;

import com.amsha.app.db.DbValidationException;
import com.amsha.app.ui.dto.DbQueryRequest;
import com.amsha.common.util.LogUtil;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.IdentityStorePort;

import org.jboss.logging.Logger;

/** Tenant isolation helper for database console queries. */
final class DbConsoleTenantResolver {

    private static final Logger log = Logger.getLogger(DbConsoleTenantResolver.class);

    private DbConsoleTenantResolver() {}

    static String resolveWorkspaceId(
            DbQueryRequest request,
            String callerUserId,
            Instance<IdentityStorePort> identityStoreInstance,
            Instance<ActiveWorkspaceContext> activeWorkspaceContextInstance,
            Instance<BootstrapStateStorePort> bootstrapStateStoreInstance) {
        String explicit = request != null
                        && request.workspaceId() != null
                        && !request.workspaceId().isBlank()
                ? request.workspaceId().trim()
                : null;
        if (explicit != null) {
            if (identityStoreInstance != null
                    && identityStoreInstance.isResolvable()
                    && !isActiveMember(explicit, callerUserId, identityStoreInstance)) {
                throw new DbValidationException("Operator is not an active member of workspace '" + explicit + "'");
            }
            return explicit;
        }
        if (activeWorkspaceContextInstance != null && activeWorkspaceContextInstance.isResolvable()) {
            var opt = activeWorkspaceContextInstance.get().workspaceId();
            if (opt.isPresent() && !opt.get().isBlank()) return opt.get().trim();
        }
        var ctxWs = WorkspaceContext.activeWorkspaceId();
        if (ctxWs.isPresent() && !ctxWs.get().isBlank()) return ctxWs.get().trim();
        String membershipWs = resolveMembershipWorkspace(callerUserId, identityStoreInstance);
        if (membershipWs != null) return membershipWs;
        return resolveBootstrapWorkspace(callerUserId, bootstrapStateStoreInstance, identityStoreInstance);
    }

    static String resolveBootstrapWorkspace(
            String callerUserId,
            Instance<BootstrapStateStorePort> bootstrapStateStoreInstance,
            Instance<IdentityStorePort> identityStoreInstance) {
        if (bootstrapStateStoreInstance == null || !bootstrapStateStoreInstance.isResolvable()) return null;
        try {
            var stateOpt = bootstrapStateStoreInstance.get().getState();
            if (stateOpt.isEmpty()) return null;
            String wsId = stateOpt.get().workspaceId();
            if (wsId == null || wsId.isBlank()) return null;
            wsId = wsId.trim();
            String ownerId = stateOpt.get().ownerId();
            if (ownerId != null && ownerId.equals(callerUserId)) return wsId;
            return isActiveMember(wsId, callerUserId, identityStoreInstance) ? wsId : null;
        } catch (Exception e) {
            log.debugf("Could not resolve workspace from bootstrap state: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    static boolean isActiveMember(
            String workspaceId, String callerUserId, Instance<IdentityStorePort> identityStoreInstance) {
        if (identityStoreInstance == null || !identityStoreInstance.isResolvable() || callerUserId == null)
            return false;
        try {
            return identityStoreInstance
                    .get()
                    .findMembership(workspaceId, callerUserId)
                    .map(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                    .orElse(false);
        } catch (Exception e) {
            log.debugf(
                    "Could not verify membership of %s in %s: %s", callerUserId, workspaceId, LogUtil.rootMessage(e));
            return false;
        }
    }

    static String resolveMembershipWorkspace(String callerUserId, Instance<IdentityStorePort> identityStoreInstance) {
        if (identityStoreInstance == null || !identityStoreInstance.isResolvable() || callerUserId == null) return null;
        try {
            var memberships = identityStoreInstance.get().listMembershipsByUserId(callerUserId);
            if (memberships != null) {
                for (var m : memberships) {
                    if ("ACTIVE".equalsIgnoreCase(m.status())
                            && m.workspaceId() != null
                            && !m.workspaceId().isBlank()) {
                        return m.workspaceId().trim();
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Could not resolve workspace from user memberships: %s", LogUtil.rootMessage(e));
        }
        return null;
    }

    static String resolveSessionId(DbQueryRequest request) {
        if (request != null
                && request.sessionId() != null
                && !request.sessionId().isBlank()) {
            return request.sessionId().trim();
        }
        return WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : null;
    }
}
