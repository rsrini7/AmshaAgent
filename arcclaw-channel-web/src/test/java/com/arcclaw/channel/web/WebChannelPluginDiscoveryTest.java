package com.arcclaw.channel.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WebChannelPluginDiscoveryTest {

    @Inject
    Instance<ChannelPlugin> plugins;

    @Test
    void testDiscovery() {
        boolean found = false;
        for (ChannelPlugin plugin : plugins) {
            if (plugin.channelType().equals(ChannelType.WEBSOCKET)) {
                found = true;
                assertNotNull(plugin.sender());
                assertNotNull(plugin.renderer());
                assertNotNull(plugin.daemon());
            }
        }
        assertTrue(found, "WebChannelPlugin should be discovered by CDI");
    }
}
