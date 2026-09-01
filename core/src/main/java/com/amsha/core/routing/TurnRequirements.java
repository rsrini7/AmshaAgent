package com.amsha.core.routing;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.loop.AgentExecutionContext;

/**
 * Turn-scoped model-selection requirements derived from the active attachments and goal, used by
 * {@link ModelRouteSelector} to choose a modality-compatible {@link ModelRoute}.
 *
 * <p>The requirements describe what a candidate model must support for this turn. They are derived
 * before any model is resolved, inverting the current role-static capability resolution so that an
 * image attachment can route to a vision model and an audio attachment to a voice model within the
 * same session.</p>
 */
public record TurnRequirements(
        Set<AttachmentType> requiredModalities,
        Set<String> mimeTypes,
        long totalPayloadBytes,
        long maxAttachmentBytes,
        int mediaCount,
        boolean requiresStructuredOutput,
        boolean isChitchat) {

    public TurnRequirements {
        requiredModalities = requiredModalities == null ? Set.of() : Set.copyOf(requiredModalities);
        mimeTypes = mimeTypes == null ? Set.of() : Set.copyOf(mimeTypes);
    }

    public static TurnRequirements textOnly() {
        return new TurnRequirements(Set.of(), Set.of(), 0L, 0L, 0, false, false);
    }

    public static TurnRequirements fromAttachments(Collection<MediaAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return textOnly();
        }
        Set<AttachmentType> modalities = new LinkedHashSet<>();
        Set<String> mimes = new LinkedHashSet<>();
        long totalBytes = 0L;
        long maxBytes = 0L;
        int count = 0;
        for (MediaAttachment attachment : attachments) {
            if (attachment.type() != null) {
                modalities.add(attachment.type());
            }
            if (attachment.mimeType() != null && !attachment.mimeType().isBlank()) {
                mimes.add(attachment.mimeType().toLowerCase(Locale.ROOT));
            }
            totalBytes += attachment.sizeBytes();
            maxBytes = Math.max(maxBytes, attachment.sizeBytes());
            count++;
        }
        return new TurnRequirements(
                Set.copyOf(modalities), Set.copyOf(mimes), totalBytes, maxBytes, count, false, false);
    }

    /**
     * Derives requirements from the active context attachments plus goal-derived signals
     * ({@code isChitchat} and {@code requiresStructuredOutput}). The goal heuristics are a
     * provisional Slab-A signal; Slab B may replace them with explicit caller-supplied intent.
     */
    public static TurnRequirements fromContext(AgentExecutionContext context, String goal) {
        TurnRequirements base = fromAttachments(context != null ? context.mediaAttachments() : List.of());
        return new TurnRequirements(
                base.requiredModalities(),
                base.mimeTypes(),
                base.totalPayloadBytes(),
                base.maxAttachmentBytes(),
                base.mediaCount(),
                requiresStructuredOutput(goal),
                isChitchat(goal));
    }

    private static boolean requiresStructuredOutput(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        String normalized = goal.toLowerCase(Locale.ROOT);
        return normalized.contains("json")
                || normalized.contains("schema")
                || normalized.contains("csv")
                || normalized.contains("extract")
                || normalized.contains("table")
                || normalized.contains("format:");
    }

    private static boolean isChitchat(String goal) {
        if (goal == null || goal.isBlank()) {
            return true;
        }
        String normalized = goal.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 15) {
            return true;
        }
        return normalized.matches(".*\\b(hello|hi|hey|clear|reset|resethard|thanks|thank you)\\b.*");
    }

    public boolean isTextOnly() {
        return requiredModalities.isEmpty();
    }
}
