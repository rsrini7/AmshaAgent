package com.amsha.selfevolve.api;

import java.util.List;

/**
 * A single extraction rule that analyzes a list of evolution events
 * from a session and produces extracted memories.
 * <p>
 * Implementations are stateless and thread-safe.
 */
@FunctionalInterface
public interface ExtractionRule {

    /**
     * Applies this rule to a session's events.
     *
     * @param events  all evolution events for the session, ordered by creation time
     * @param userId  the user who owns the session
     * @return list of extracted memories, empty if no patterns found
     */
    List<ExtractedMemory> apply(List<EvolutionEvent> events, String userId);
}
