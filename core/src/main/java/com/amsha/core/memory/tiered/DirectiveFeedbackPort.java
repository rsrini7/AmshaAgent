package com.amsha.core.memory.tiered;

import java.util.List;

/**
 * Optional feedback hook for self-evolve behavioral directives.
 * Core emits prompt-injection and terminal-outcome signals through this port
 * without depending directly on the self-evolve module.
 */
public interface DirectiveFeedbackPort {

    /**
     * Records that shadow directives were injected into prompts for the given session.
     */
    void noteShadowDirectivesInjected(String sessionId, String userId, List<String> shadowDirectiveIds);

    /**
     * Records the terminal outcome for a session that had directive injection.
     */
    void recordSessionOutcome(String sessionId, String userId, boolean success);
}
