package com.amsha.core.agent.role;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;

/**
 * Drift guard for the S4/S5 extraction: ToolCapability.DELEGATABLE_ROLE_NAMES is a pinned
 * copy of core's dynamic persona vocabulary. If a role or alias changes in core, update the
 * pinned constant in tool-api so prompt text stays identical.
 */
public class CoreRoleVocabularyDriftTest {

    @Test
    public void toolApiDelegatableNamesMatchCorePersonaVocabulary() {
        assertEquals(
                AgentRoleEnum.getDelegatableRoleNames(),
                ToolCapability.DELEGATABLE_ROLE_NAMES,
                "tool-api pin drifted from core persona vocabulary - regenerate and update "
                        + "ToolCapability.DELEGATABLE_ROLE_NAMES");
    }
}
