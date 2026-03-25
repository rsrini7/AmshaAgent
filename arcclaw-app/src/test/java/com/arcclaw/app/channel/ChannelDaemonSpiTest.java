package com.arcclaw.app.channel;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.ChannelDaemon;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ChannelDaemonSpiTest {

    @Inject
    @Any
    Instance<ChannelDaemon> daemons;

    @Test
    void testTelegramChannelDaemonDiscoveredByCdi() {
        List<ChannelDaemon> daemonList = daemons.stream().collect(Collectors.toList());
        
        Assertions.assertFalse(daemonList.isEmpty(), "At least one ChannelDaemon should be discovered");
        
        boolean hasTelegram = daemonList.stream()
            .anyMatch(d -> d.channelId().equals("telegram"));
            
        Assertions.assertTrue(hasTelegram, "TelegramChannelDaemon should be discovered via CDI");
    }
}
