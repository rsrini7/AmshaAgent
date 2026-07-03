package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import com.amsha.core.agent.role.CapabilityRegistry;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that the default {@code amsha.tools.host-exec.skills=jdb-debugger}
 * config results in the {@code skill:jdb-debugger} role receiving the
 * {@code host_exec} tool capability at startup, while unrelated skill roles
 * do not.
 */
@QuarkusTest
class HostExecSkillGrantTest {

    @Inject
    CapabilityRegistry capabilityRegistry;

    @Test
    void jdbDebuggerSkillReceivesHostExec() {
        assertTrue(
                capabilityRegistry.getSupplementalTools("skill:jdb-debugger").contains("host_exec"),
                "skill:jdb-debugger should have host_exec granted via amsha.tools.host-exec.skills");
    }

    @Test
    void unrelatedSkillDoesNotReceiveHostExec() {
        assertFalse(
                capabilityRegistry
                        .getSupplementalTools("skill:some-other-skill")
                        .contains("host_exec"),
                "skill:some-other-skill should NOT have host_exec granted");
    }
}
