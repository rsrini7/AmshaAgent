package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliInputSupportTest {

    @Test
    void identifiesExitCommandsWithoutRepeatingCommandTokens() {
        assertTrue(CliInputSupport.isExitCommand(CliInputSupport.EXIT_COMMAND));
        assertTrue(CliInputSupport.isExitCommand(CliInputSupport.QUIT_COMMAND.toUpperCase()));
        assertFalse(CliInputSupport.isExitCommand("hello"));
    }

    @Test
    void identifiesApprovalCommandsAndAllowsTheirArguments() {
        assertTrue(CliInputSupport.isApprovalCommand(CliInputSupport.APPROVE_COMMAND));
        assertTrue(CliInputSupport.isApprovalCommand(CliInputSupport.DENY_COMMAND + " 17"));
        assertFalse(CliInputSupport.isApprovalCommand(null));
        assertFalse(CliInputSupport.isApprovalCommand("hello"));
    }
}
