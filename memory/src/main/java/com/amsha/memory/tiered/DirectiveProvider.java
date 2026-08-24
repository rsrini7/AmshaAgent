package com.amsha.memory.tiered;

import java.util.List;

/**
 * Port for retrieving synthesized behavioral directives for prompt injection.
 * Implemented by the self-evolve module. Returns empty when self-evolve is disabled.
 * <p>
 * This interface lives in amsha-memory (extracted from core in S8) so both core and
 * self-evolve can depend on it without cycles.
 */
public interface DirectiveProvider {

    record DirectiveInfo(String id, String text) {}

    record DirectivesResult(List<DirectiveInfo> active, List<DirectiveInfo> shadow) {}

    /**
     * Returns both active and shadow directives for prompt injection.
     *
     * @param userId user to get directives for
     * @return DirectivesResult containing active and shadow directives
     */
    DirectivesResult getDirectives(String userId);
}
