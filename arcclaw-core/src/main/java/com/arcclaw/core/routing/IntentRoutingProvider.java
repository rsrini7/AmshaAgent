package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SPI for providing intent routing rules (fast-path triggers and system prompt directives)
 * without modifying the core IntentClassifierService.
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
}
