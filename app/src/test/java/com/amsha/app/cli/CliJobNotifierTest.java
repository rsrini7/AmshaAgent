package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.*;

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
        assertDoesNotThrow(() -> notifier.notifyApprovalTimeout("user", "s1", "host_exec", 5));
    }
}
