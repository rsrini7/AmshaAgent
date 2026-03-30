package com.arcclaw.channel.web;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;

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
