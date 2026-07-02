package com.amsha.core.memory.tiered;

import java.util.List;

/**
 * Port for retrieving synthesized behavioral directives for prompt injection.
 * Implemented by the self-evolve module. Returns empty when self-evolve is disabled.
 * <p>
 * This interface lives in core to avoid circular dependencies (self-evolve depends on core).
 */
public interface DirectiveProvider {

    /**
     * Returns directive texts eligible for prompt injection.
     *
     * @param userId user to get directives for
     * @return list of directive texts (max 5, each max 600 chars)
     */
    List<String> getInjectableDirectives(String userId);

    /**
     * Returns shadow directive texts for evaluation injection.
     * These are prefixed with [EVAL] in the prompt.
     *
     * @param userId user to get directives for
     * @return list of shadow directive texts
     */
    List<String> getShadowDirectives(String userId);
}
