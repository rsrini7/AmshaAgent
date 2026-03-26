package com.arcclaw.app.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class ChannelPluginRegistryTest {

    @Inject ChannelPluginRegistry registry;

    @Test
    void allFiveCapabilitiesAreNonNull() {
        ChannelPlugin plugin = registry.find(ChannelType.TELEGRAM).orElseThrow();
        assertNotNull(plugin.sender());
        assertNotNull(plugin.renderer());
        assertNotNull(plugin.allowlist());
        assertNotNull(plugin.jobNotifier());
        assertNotNull(plugin.daemon());
    }

    @Test
    void unknownChannelReturnsEmpty() {
        assertFalse(registry.find("discord").isPresent());
    }
}
