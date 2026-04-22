package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SPI for providing intent routing rules (fast-path triggers and system prompt directives)
 * without modifying the core IntentDetectorService.
 */
public interface IntentRoutingProvider {
    /**
     * Zero or more fast-path trigger patterns for this provider.
     * If any matches, routing skips LLM classification.
     */
    List<Pattern> fastPathTriggers();

    /**
     * Directives injected into the classifier system prompt.
     * Return empty list if none.
     */
    List<String> promptDirectives();

    /**
     * Return Optional.empty() if this provider does not own a fast path.
     */
    Optional<String> fastPathRole();

    /**
     * Priority for evaluation (lower values are evaluated first).
     */
    default int priority() {
        return 100;
    }

    /**
     * Returns true if this provider should skip fast-path matching for the
     * given message (e.g. if the message contains a negation). §4.11
     */
    default boolean shouldSkip(String userMessage) {
        return false;
    }

    /**
     * Returns true if the message matches a fuzzy pattern for this provider.
     */
    default boolean matchesFuzzy(String userMessage) {
        return false;
    }
}
