package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

/**
 * Default {@link ModelRouteSelector}: keeps only candidates covering every required modality, picks
 * the highest-priority qualifying candidate as primary, and adds the remaining qualifying candidates
 * that also satisfy the turn's required modalities as same-modality fallbacks. For text-only turns it
 * ranks pure text-capable routes first so a vision or audio model does not win over the lightweight
 * text route. When no candidate covers the requirements, the turn must degrade to a bounded text
 * placeholder rather than failing the session (fail-closed).
 */
@ApplicationScoped
public class DefaultModelRouteSelector implements ModelRouteSelector {

    private static final Logger log = Logger.getLogger(DefaultModelRouteSelector.class);

    @Override
    public Optional<ModelRoute> select(String agentRole, TurnRequirements requirements, List<ModelRoute> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<ModelRoute> qualifying = new ArrayList<>();
        for (ModelRoute candidate : candidates) {
            if (candidate != null && candidate.covers(requirements)) {
                qualifying.add(candidate);
            }
        }
        if (qualifying.isEmpty()) {
            log.warnf(
                    "[ROUTE-SELECTOR] No candidate covers required modalities %s for role %s; turn degrades to text fallback",
                    requirements != null ? requirements.requiredModalities() : "[]", agentRole);
            return Optional.empty();
        }
        List<ModelRoute> ordered = orderForSelection(requirements, qualifying);
        ModelRoute primary = ordered.getFirst();
        List<ModelRoute> fallbacks = ordered.stream()
                .skip(1)
                .filter(candidate -> candidate.canFallbackFor(requirements))
                .toList();
        return Optional.of(primary.withFallbacks(fallbacks));
    }

    private List<ModelRoute> orderForSelection(TurnRequirements requirements, List<ModelRoute> routes) {
        if (!requirements.isTextOnly()) {
            return routes;
        }
        List<ModelRoute> ordered = new ArrayList<>(routes);
        ordered.sort((a, b) -> Boolean.compare(
                a.capability() != null && a.capability().isMultimodal(),
                b.capability() != null && b.capability().isMultimodal()));
        return ordered;
    }
}
