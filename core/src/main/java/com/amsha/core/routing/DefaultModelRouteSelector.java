package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.llm.api.StrictJsonSchemaCapable;

import org.jboss.logging.Logger;

/**
 * Default {@link ModelRouteSelector}: keeps only candidates covering every required modality, then
 * filters further by MIME type, payload size, and media-count constraints where the capability
 * contract provides them. Picks the highest-priority qualifying candidate as primary, and adds the
 * remaining qualifying candidates that also satisfy the turn's required modalities as same-modality
 * fallbacks. For text-only turns it ranks pure text-capable routes first so a vision or audio model
 * does not win over the lightweight text route. When no candidate covers the requirements, the turn
 * must degrade to a bounded text placeholder rather than failing the session (fail-closed).
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
            if (candidate != null && qualifies(candidate, requirements)) {
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

    /**
     * A candidate qualifies when it covers every required modality AND satisfies the MIME type,
     * payload size, and media-count constraints declared in its capability. Unknown or zero-valued
     * limits are treated as unbounded (pass).
     */
    private boolean qualifies(ModelRoute candidate, TurnRequirements requirements) {
        if (!candidate.covers(requirements)) {
            return false;
        }
        if (requirements == null || requirements.isTextOnly()) {
            return true;
        }
        return satisfiesMimeConstraints(candidate, requirements)
                && satisfiesSizeConstraint(candidate, requirements)
                && satisfiesMediaCountConstraint(candidate, requirements);
    }

    private boolean satisfiesMimeConstraints(ModelRoute candidate, TurnRequirements requirements) {
        if (requirements.mimeTypes().isEmpty()) {
            return true;
        }
        if (candidate.capability() == null) {
            return false;
        }
        for (String requiredMime : requirements.mimeTypes()) {
            if (!candidate.capability().supportsMimeType(requiredMime)) {
                return false;
            }
        }
        return true;
    }

    private boolean satisfiesSizeConstraint(ModelRoute candidate, TurnRequirements requirements) {
        if (requirements.maxAttachmentBytes() <= 0L) {
            return true;
        }
        if (candidate.capability() == null || candidate.capability().maxFileSize() <= 0L) {
            // Zero/unknown limit treated as unbounded
            return true;
        }
        return requirements.maxAttachmentBytes() <= candidate.capability().maxFileSize();
    }

    private boolean satisfiesMediaCountConstraint(ModelRoute candidate, TurnRequirements requirements) {
        if (requirements.mediaCount() <= 0) {
            return true;
        }
        if (candidate.capability() == null || candidate.capability().maxMediaPerRequest() <= 0) {
            // Zero/unknown limit treated as unbounded
            return true;
        }
        return requirements.mediaCount() <= candidate.capability().maxMediaPerRequest();
    }

    private List<ModelRoute> orderForSelection(TurnRequirements requirements, List<ModelRoute> routes) {
        if (requirements == null) {
            return routes;
        }
        List<ModelRoute> ordered = new ArrayList<>(routes);
        ordered.sort((a, b) -> {
            if (requirements.isTextOnly() || requirements.isChitchat()) {
                // Prefer pure text-capable routes first on text-only and chitchat turns
                int textCmp = Boolean.compare(
                        a.capability() != null && a.capability().isMultimodal(),
                        b.capability() != null && b.capability().isMultimodal());
                if (textCmp != 0) {
                    return textCmp;
                }
            }
            if (requirements.requiresStructuredOutput()) {
                boolean aSchema = supportsStructured(a);
                boolean bSchema = supportsStructured(b);
                int schemaCmp = Boolean.compare(!aSchema, !bSchema);
                if (schemaCmp != 0) {
                    return schemaCmp;
                }
            }
            return 0;
        });
        return ordered;
    }

    private static boolean supportsStructured(ModelRoute route) {
        if (route == null || route.model() == null) {
            return false;
        }
        return route.model() instanceof StrictJsonSchemaCapable capable && capable.supportsStrictJsonSchema();
    }
}
