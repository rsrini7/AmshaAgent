package com.amsha.selfevolve.api;

import java.util.List;

/**
 * A bundle of directives returned for prompt injection.
 * Separates ACTIVE directives (authoritative) from SHADOW directives (evaluation).
 *
 * @param activeDirectives  directives in ACTIVE state, injected as [BEHAVIORAL DIRECTIVES]
 * @param shadowDirectives  directives in SHADOW state, injected as [BEHAVIORAL DIRECTIVES - EVALUATION]
 * @param totalAvailable    total injectable directives before limit was applied
 */
public record DirectiveBundle(
        List<SynthesizedDirective> activeDirectives, List<SynthesizedDirective> shadowDirectives, int totalAvailable) {

    /**
     * Returns true if there are any injectable directives.
     */
    public boolean hasDirectives() {
        return !activeDirectives.isEmpty() || !shadowDirectives.isEmpty();
    }

    /**
     * Returns the total number of directives in this bundle.
     */
    public int size() {
        return activeDirectives.size() + shadowDirectives.size();
    }
}
