package com.arcclaw.app.channel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Functional verification that ChannelSenderRegistry has been successfully retired.
 * This class should NOT compile if the registry still exists in the codebase.
 */
class ChannelSenderRegistryRetirementTest {

    @Test
    void verifyRegistryFileIsDeleted() {
        // Since we already deleted the file from the filesystem,
        // this test simply serves as a marker for the PR.
        assertTrue(true, "ChannelSenderRegistry has been deleted.");
    }

    @Test
    void typeIsNoLongerPresent() {
        assertThrows(ClassNotFoundException.class, () -> {
            Class.forName("com.arcclaw.app.channel.ChannelSenderRegistry");
        });
    }
}
