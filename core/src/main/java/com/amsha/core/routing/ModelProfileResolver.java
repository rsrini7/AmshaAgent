package com.amsha.core.routing;

import java.util.Optional;

/**
 * Resolves a deterministic, ordered model-profile chain for a runtime routing target.
 *
 * <p>Implements the normative {@code PROFILE_ROUTE_PRECEDENCE} chain from the onboarding ADR:
 * owner role → owner role group → owner DEFAULT → active-workspace role → active-workspace role
 * group → active-workspace DEFAULT → lightweight deployment override → deployment persona →
 * deployment group → deployment global → migrated SYSTEM profile → legacy system vault.</p>
 *
 * <p>An unassigned profile is never selected. Resolution is read-only and never constructs a
 * ChatModel; the caller builds clients from {@link ResolvedProfile#credentialView()} via the
 * runtime client factory. The resolver records nothing about health.</p>
 */
public interface ModelProfileResolver {

    /**
     * Resolves the ordered chain for a given role under the current identity context.
     * Returns an empty result when no eligible owned/workspace/system route applies, so the
     * caller falls back to legacy deployment/system routing.
     */
    RouteResolution resolve(String agentRole);

    /**
     * Resolves the ordered chain for an explicit scope (used by runtime/system fallback paths).
     */
    RouteResolution resolve(String scopeType, String scopeId, String targetKind, String targetId);

    /**
     * Looks up a single profile by id (used to validate assignments and to build a client).
     */
    Optional<ResolvedProfile> resolveProfile(String profileId);
}
