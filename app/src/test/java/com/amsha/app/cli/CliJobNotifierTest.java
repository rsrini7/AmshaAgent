package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import com.amsha.channel.api.ChannelType;

import org.junit.jupiter.api.Test;

class CliJobNotifierTest {

    private final CliJobNotifier notifier = new CliJobNotifier();

    @Test
    void testSupports() {
        assertTrue(notifier.supports(ChannelType.CLI));
        assertFalse(notifier.supports(ChannelType.TELEGRAM));
    }

    @Test
    void testNotifications() {
        assertDoesNotThrow(() -> notifier.notifyJobCompleted("user", "Job done", "s1"));
        assertDoesNotThrow(() -> notifier.notifyApprovalRequired("user", "s1", "host_exec", "Details"));
        assertDoesNotThrow(() -> notifier.notifyApprovalRequired(
                "user", "s1", "host_exec", "Details\nThis approval belongs to a background cron session"));
        assertDoesNotThrow(() -> notifier.notifyApprovalTimeout("user", "s1", "host_exec", 5));
    }

    @Test
    void testTerminalPrinter() {
        List<String> output = new ArrayList<>();
        CliJobNotifier.setTerminalPrinter(output::add);
        try {
            notifier.notifyJobCompleted("user", "Finished factorial", "s1");
            notifier.notifyApprovalRequired("user", "s1", "sandbox_exec", "Details");
            notifier.notifyApprovalTimeout("user", "s1", "sandbox_exec", 10);

            assertEquals(3, output.size());
            assertTrue(output.get(0).contains("[Cron Result] Finished factorial"));
            assertTrue(output.get(1).contains("[Cron Approval Required] Tool: sandbox_exec"));
            assertTrue(output.get(2).contains("[Cron Approval Timeout] Tool 'sandbox_exec' timed out"));
        } finally {
            CliJobNotifier.setTerminalPrinter(null);
        }
    }
}
