package com.arcclaw.app.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.app.channel.telegram.TelegramChannelPlugin;
import com.arcclaw.channel.api.ChannelType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChannelPluginRegistryTest {

    @Inject ChannelPluginRegistry registry;

    @Test
    void telegramPluginIsRegisteredAtStartup() {
        Optional<ChannelPlugin> telegram = registry.find(ChannelType.TELEGRAM);
        assertTrue(telegram.isPresent());
        assertTrue(telegram.get() instanceof TelegramChannelPlugin);
    }

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
