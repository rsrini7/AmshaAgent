package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.util.MdcKeys;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Core implementation of {@link ModelProfileResolver} honoring the normative PROFILE_ROUTE_PRECEDENCE.
 *
 * <p>Resolution is performed only against {@code USER}/{@code WORKSPACE}/{@code SYSTEM} scoped routes
 * owned by (or granted to) the current identity. Unknown roles/groups are skipped rather than
 * matched to a near value. An unassigned profile is never selected.</p>
 */
@ApplicationScoped
public class ModelProfileResolverImpl implements ModelProfileResolver {

    private static final Logger log = Logger.getLogger(ModelProfileResolverImpl.class);

    /**
     * Reserved scope ID for {@code SYSTEM}-scoped routes. SYSTEM is operator-managed (legacy
     * migration / bootstrap) and not owned by any human; its routes are matched globally by
     * DEFAULT target.
     */
    private static final String SYSTEM_SCOPE_ID = "SYSTEM";

    @Inject
    LlmProfileStorePort profileStore;

    @Inject
    WorkspaceConnectionGrantStorePort grantStore;

    @Inject
    AgentGroupRegistry groupRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    ActiveWorkspaceContext activeWorkspaceContext;

    @Override
    public RouteResolution resolve(String agentRole) {
        String ownerId = currentUserId();
        String workspaceId = resolveActiveWorkspaceId().orElse(null);
        log.debugf("[PROFILE-ROUTER] resolve(role=%s) owner=%s workspace=%s", agentRole, ownerId, workspaceId);

        // 1. Owner role
        Optional<RouteResolution> ownerRole = resolveForTarget("USER", ownerId, "ROLE", canonicalRole(agentRole));
        if (ownerRole.isPresent()) {
            log.debugf("[PROFILE-ROUTER] matched tier=%s", ownerRole.get().precedenceTier());
            return ownerRole.get();
        }

        // 2. Owner role group
        Optional<String> groupId = groupRegistry.findByMember(agentRole).map(g -> g.groupId());
        if (groupId.isPresent()) {
            Optional<RouteResolution> ownerGroup = resolveForTarget("USER", ownerId, "GROUP", groupId.get());
            if (ownerGroup.isPresent()) {
                log.debugf("[PROFILE-ROUTER] matched tier=%s", ownerGroup.get().precedenceTier());
                return ownerGroup.get();
            }
        }

        // 3. Owner DEFAULT
        Optional<RouteResolution> ownerDefault = resolveForTarget("USER", ownerId, "DEFAULT", null);
        if (ownerDefault.isPresent()) {
            log.debugf("[PROFILE-ROUTER] matched tier=%s", ownerDefault.get().precedenceTier());
            return ownerDefault.get();
        }

        // 4-6. Active workspace role / group / DEFAULT
        if (workspaceId != null) {
            Optional<RouteResolution> wsRole =
                    resolveForTarget("WORKSPACE", workspaceId, "ROLE", canonicalRole(agentRole));
            if (wsRole.isPresent()) {
                log.debugf("[PROFILE-ROUTER] matched tier=%s", wsRole.get().precedenceTier());
                return wsRole.get();
            }
            if (groupId.isPresent()) {
                Optional<RouteResolution> wsGroup = resolveForTarget("WORKSPACE", workspaceId, "GROUP", groupId.get());
                if (wsGroup.isPresent()) {
                    log.debugf("[PROFILE-ROUTER] matched tier=%s", wsGroup.get().precedenceTier());
                    return wsGroup.get();
                }
            }
            Optional<RouteResolution> wsDefault = resolveForTarget("WORKSPACE", workspaceId, "DEFAULT", null);
            if (wsDefault.isPresent()) {
                log.debugf("[PROFILE-ROUTER] matched tier=%s", wsDefault.get().precedenceTier());
                return wsDefault.get();
            }
        }

        // 7. SYSTEM (operator-managed / legacy migration) — last-resort profile route before the
        // caller's legacy deployment/system fallback. SYSTEM routes are global and owned by the
        // platform, so they are matched by DEFAULT target only.
        Optional<RouteResolution> systemRoute = resolveForTarget("SYSTEM", SYSTEM_SCOPE_ID, "DEFAULT", null);
        if (systemRoute.isPresent()) {
            log.debugf("[PROFILE-ROUTER] matched tier=%s", systemRoute.get().precedenceTier());
            return systemRoute.get();
        }

        // 8+. Deployment/system fallback is handled by the caller (ModelRouter).
        log.debugf("[PROFILE-ROUTER] no owned/workspace/system route for role=%s; falling back to legacy", agentRole);
        return RouteResolution.empty("no-owned-route");
    }

    @Override
    public RouteResolution resolve(String scopeType, String scopeId, String targetKind, String targetId) {
        return resolveForTarget(scopeType, scopeId, targetKind, targetId)
                .orElseGet(() -> RouteResolution.empty("no-route"));
    }

    @Override
    public Optional<ResolvedProfile> resolveProfile(String profileId) {
        Optional<ModelProfileDto> profile = profileStore.findModelProfile(profileId);
        if (profile.isEmpty() || !profile.get().enabled()) {
            return Optional.empty();
        }
        Optional<LlmConnectionDto> connection =
                profileStore.findConnection(profile.get().connectionId());
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        // A profile is only routable when its backing connection is in an ACTIVE state. STAGED,
        // disabled, or otherwise non-active connections must not be selected by any route tier.
        LlmConnectionDto c = connection.get();
        if (!"ACTIVE".equalsIgnoreCase(c.state())) {
            return Optional.empty();
        }
        ModelProfileDto p = profile.get();
        return Optional.of(new ResolvedProfile(
                p.id(),
                c.id(),
                p.modelId(),
                c.providerAdapterId(),
                c.scopeType(),
                c.scopeId(),
                c.revision(),
                p.revision(),
                p.enabled()));
    }

    private Optional<RouteResolution> resolveForTarget(
            String scopeType, String scopeId, String targetKind, String targetId) {
        if (!isTargetEligible(targetKind, targetId)) {
            // Unknown dynamic skill role (or otherwise unresolvable target): reject rather than
            // match a near value or an unregistered skill:* target.
            return Optional.empty();
        }
        List<ModelRouteAssignmentDto> routes = profileStore.listRoutesByScope(scopeType, scopeId);
        Optional<ModelRouteAssignmentDto> match = routes.stream()
                .filter(r -> targetKind.equals(r.targetKind()))
                .filter(r -> targetId == null ? r.targetId() == null : targetId.equals(r.targetId()))
                .filter(ModelRouteAssignmentDto::enabled)
                .findFirst();
        if (match.isEmpty()) {
            return Optional.empty();
        }
        List<ResolvedProfile> chain = buildChain(match.get());
        if (chain.isEmpty()) {
            return Optional.empty();
        }
        String tier = scopeType.toLowerCase(Locale.ROOT) + "-" + targetKind.toLowerCase(Locale.ROOT);
        return Optional.of(RouteResolution.of(tier, chain));
    }

    /**
     * Validates a routing target before matching. A {@code ROLE} target that is a dynamic
     * {@code skill:*} role must be explicitly registered in {@link RoleRegistry}; an unregistered
     * dynamic role is rejected (no near-match persistence). Built-in roles, groups, and the
     * {@code DEFAULT} (null target) always pass.
     */
    private boolean isTargetEligible(String targetKind, String targetId) {
        if (!"ROLE".equals(targetKind) || targetId == null) {
            return true;
        }
        if (AgentRoleEnum.isSkillRole(targetId)) {
            return roleRegistry.isRegisteredDynamic(targetId);
        }
        return true;
    }

    private List<ResolvedProfile> buildChain(ModelRouteAssignmentDto assignment) {
        List<ResolvedProfile> chain = new ArrayList<>();
        for (String profileId : assignment.orderedProfileIds()) {
            resolveProfile(profileId).ifPresent(chain::add);
        }
        return chain;
    }

    private String canonicalRole(String agentRole) {
        try {
            return AgentRoleEnum.fromString(agentRole).toScopedName();
        } catch (IllegalArgumentException e) {
            return agentRole;
        }
    }

    private String currentUserId() {
        if (WorkspaceContext.USER_ID.isBound() && WorkspaceContext.USER_ID.get() != null) {
            return WorkspaceContext.USER_ID.get();
        }
        String mdcUser = (String) MDC.get(MdcKeys.USER_ID);
        return mdcUser != null ? mdcUser : "unknown";
    }

    /**
     * Resolves the active identity workspace id for routing. Prefers the value propagated through
     * the context-aware executor / {@code WorkspaceContext.run} chain (available in async
     * agent/channel/cron execution), then falls back to the {@code @RequestScoped}
     * {@code ActiveWorkspaceContext} only when a request context is active. This avoids the
     * {@code ContextNotActiveException} that the request-scoped bean throws outside an HTTP request
     * while keeping request-time workspace selection working.
     */
    private Optional<String> resolveActiveWorkspaceId() {
        Optional<String> propagated = WorkspaceContext.activeWorkspaceId();
        if (propagated.isPresent()) {
            return propagated;
        }
        try {
            return activeWorkspaceContext.workspaceId();
        } catch (ContextNotActiveException e) {
            return Optional.empty();
        }
    }
}
