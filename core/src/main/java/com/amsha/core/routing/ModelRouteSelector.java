package com.amsha.core.routing;

import java.util.List;
import java.util.Optional;

/**
 * Selects a modality-compatible {@link ModelRoute} for a single turn.
 *
 * <p>Implementations filter candidate routes to those covering every required modality, choose a
 * primary, and assemble same-modality fallbacks so the downstream {@code OrderedFailoverChatModel}
 * intersection stays meaningful. When no candidate covers the requirements, the turn must degrade to
 * a bounded text placeholder rather than failing the session (fail-closed).</p>
 */
public interface ModelRouteSelector {

    Optional<ModelRoute> select(String agentRole, TurnRequirements requirements, List<ModelRoute> candidates);
}
