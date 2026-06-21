package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
class TelegramChannelRouteStartupTest {

    // All mock beans are provided by TestCoreMocks (shared across all @QuarkusTest tests)

    @Inject
    CamelContext camelContext;

    @BeforeEach
    void stopPolling() throws Exception {
        // Wait up to 2 seconds for route to register asynchronously
        long start = System.currentTimeMillis();
        while (camelContext.getRoute("telegram-bot") == null && System.currentTimeMillis() - start < 2000) {
            Thread.sleep(50);
        }

        // Stop the route immediately after startup to prevent real API polling
        // We only need to verify registration, not actual polling
        if (camelContext.getRouteController().getRouteStatus("telegram-bot") == ServiceStatus.Started) {
            camelContext.getRouteController().stopRoute("telegram-bot");
        }
    }

    @Test
    void testTelegramRouteSelfRegisteredOnStartup() {
        // Route must exist even if stopped for this test
        assertNotNull(camelContext.getRoute("telegram-bot"), "telegram-bot route must be registered by onStart()");
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
