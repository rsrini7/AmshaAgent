package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.MultimodalCapability;

/**
 * An immutable, modality-compatible model route selected for a single turn.
 *
 * <p>Carries the resolved routing identity ({@link ResolvedProfile}), the lazily pooled
 * {@link ChatModel}, and the explicit {@link MultimodalCapability} covering this turn's requirements.
 * {@link #fallbackRoutes()} holds same-modality candidates so the downstream failover wrapper's
 * safe capability intersection stays meaningful (a modality-mixed chain would collapse to
 * {@link com.amsha.llm.api.MultimodalCapability#TEXT_ONLY}).</p>
 */
public record ModelRoute(
        String routeId,
        String providerName,
        String modelName,
        ResolvedProfile profile,
        ChatModel model,
        MultimodalCapability capability,
        List<ModelRoute> fallbackRoutes,
        long profileRevision) {

    public ModelRoute {
        if (routeId == null || routeId.isBlank()) {
            throw new IllegalArgumentException("routeId must not be null or blank");
        }
        fallbackRoutes = fallbackRoutes == null ? List.of() : List.copyOf(fallbackRoutes);
    }

    public boolean supports(AttachmentType modality) {
        return capability != null && capability.supportsType(modality);
    }

    /** True when this route covers every modality required by {@code requirements}. */
    public boolean covers(TurnRequirements requirements) {
        if (requirements == null || requirements.isTextOnly()) {
            return true;
        }
        for (AttachmentType required : requirements.requiredModalities()) {
            if (!supports(required)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when this route can serve as a failover for {@code requirements}: it supports every
     * modality the turn requires, so the downstream capability intersection stays non-empty.
     */
    public boolean canFallbackFor(TurnRequirements requirements) {
        return covers(requirements);
    }

    /** Returns a copy of this route with the given fallback list (defensively copied). */
    public ModelRoute withFallbacks(List<ModelRoute> fallbacks) {
        return new ModelRoute(routeId, providerName, modelName, profile, model, capability, fallbacks, profileRevision);
    }
}
