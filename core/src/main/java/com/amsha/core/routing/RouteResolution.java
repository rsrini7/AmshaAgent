package com.amsha.core.routing;

import java.util.List;

/**
 * Result of {@link ModelProfileResolver#resolve}. Contains the ordered, eligible profile chain
 * for the highest-precedence matching scope, or an empty chain when no owned/workspace/system route
 * applies. {@code precedenceTier} records which normative tier matched, for diagnostics/health.
 */
public record RouteResolution(boolean resolved, String precedenceTier, List<ResolvedProfile> chain) {

    public static RouteResolution empty(String tier) {
        return new RouteResolution(false, tier, List.of());
    }

    public static RouteResolution of(String tier, List<ResolvedProfile> chain) {
        return new RouteResolution(true, tier, List.copyOf(chain));
    }
}
