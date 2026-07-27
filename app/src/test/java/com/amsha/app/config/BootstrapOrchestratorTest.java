package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BootstrapOrchestratorTest {

    @Test
    void testBootstrapExecution() {
        assertDoesNotThrow(() -> BootstrapOrchestrator.bootstrap(new String[] {"--help"}));
    }
}
