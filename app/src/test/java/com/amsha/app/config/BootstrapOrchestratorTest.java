package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BootstrapOrchestratorTest {

    @Test
    void shouldNotReexecForInformationalInvocationsOnLinux() {
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"--help"}, "Linux", null, null));
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"--version"}, "Linux", null, null));
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"-h"}, "Linux", null, null));
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"-v"}, "Linux", null, null));
    }

    @Test
    void shouldReexecOnlyForNormalLinuxInvocationsWithoutPreload() {
        assertTrue(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"serve"}, "Linux", null, null));
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"serve"}, "Darwin", null, null));
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(new String[] {"serve"}, "Linux", "1", null));
        assertFalse(BootstrapOrchestrator.shouldReexecWithLdPreload(
                new String[] {"serve"}, "Linux", null, "/lib/libm.so.6"));
    }

    @Test
    void testBootstrapExecution() {
        assertDoesNotThrow(() -> BootstrapOrchestrator.bootstrap(new String[] {"--help"}));
    }
}
