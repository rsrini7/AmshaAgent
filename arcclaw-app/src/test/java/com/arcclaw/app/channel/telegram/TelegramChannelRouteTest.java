package com.arcclaw.app.channel.telegram;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TelegramChannelRouteTest {

        @Inject
        CamelContext camelContext;

        @Inject
        TelegramChannelRoute route;

        @Test
        void testRouteExists() throws Exception {
            if (camelContext.getRoutes().isEmpty()) {
                camelContext.addRoutes(route);
                camelContext.start();
            }
            boolean routeExists = camelContext.getRoutes().stream()
                            .map(Route::getEndpoint)
                            .map(Object::toString)
                            .anyMatch(uri -> uri.contains("telegram"));

            assertTrue(routeExists, "Telegram route should be registered in CamelContext");
        }
}
