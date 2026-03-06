package com.arcclaw.app.channel.telegram;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @Inject Logger logger;
    @Inject CamelContext camelContext;

    @Test
    void testTelegramRouteSelfRegisteredOnStartup() {
        // The onStart(@Observes StartupEvent) should have fired and registered the route
        logger.infof("Test checking for route. Context status: %s", camelContext.getStatus());
        Route route = camelContext.getRoute("telegram-bot");
        if (route == null) {
            logger.warnf("Route 'telegram-bot' not found. Available routes: %s",
                camelContext.getRoutes().stream().map(r -> r.getId()).toList());
        }
        assertNotNull(route, "telegram-bot route must be registered by TelegramChannelRoute.onStart()");
        assertEquals(ServiceStatus.Started, camelContext.getRouteController().getRouteStatus("telegram-bot"), "telegram-bot route must be started");
    }

    @Test
    void testTelegramRouteStartedNotJustAdded() {
        Route route = camelContext.getRoute("telegram-bot");
        assertNotNull(route);
        assertEquals("telegram-bot", route.getId());
        assertEquals(ServiceStatus.Started, camelContext.getRouteController().getRouteStatus("telegram-bot"));
    }
}
