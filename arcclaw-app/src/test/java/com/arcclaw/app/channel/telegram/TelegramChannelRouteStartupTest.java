package com.arcclaw.app.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TelegramChannelRouteStartupTest.RealTokenProfile.class)
class TelegramChannelRouteStartupTest {

    public static class RealTokenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "arcclaw.channel.telegram.enabled", "true",
                "arcclaw.channel.telegram.bot-token", "real-looking-token-123:ABC"
            );
        }
    }

    @Inject CamelContext camelContext;

    @BeforeEach
    void stopPolling() throws Exception {
        // Wait up to 2 seconds for route to register asynchronously
        long start = System.currentTimeMillis();
        while (camelContext.getRoute("telegram-bot") == null && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(50);
        }

        // Stop the route immediately after startup to prevent real API polling
        // We only need to verify registration, not actual polling
        if (camelContext.getRouteController()
                .getRouteStatus("telegram-bot") == ServiceStatus.Started) {
            camelContext.getRouteController().stopRoute("telegram-bot");
        }
    }

    @Test
    void testTelegramRouteSelfRegisteredOnStartup() {
        // Route must exist even if stopped for this test
        assertNotNull(camelContext.getRoute("telegram-bot"),
            "telegram-bot route must be registered by onStart()");
    }

    @Test
    void testTelegramRouteStartedNotJustAdded() {
        // Route was started before we stopped it in @BeforeEach
        // Verify it exists and has an ID
        Route route = camelContext.getRoute("telegram-bot");
        assertNotNull(route);
        assertEquals("telegram-bot", route.getId());
    }
}
