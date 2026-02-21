package com.arcclaw.app.channel.telegram;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(TelegramChannelRouteTest.TelegramEnabledProfile.class)
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

        public static class TelegramEnabledProfile implements QuarkusTestProfile {
            @Override
            public Map<String, String> getConfigOverrides() {
                return Map.of(
                        "arcclaw.channel.telegram.enabled",
                        "true",
                        "quarkus.log.category.\"org.apache.camel.component.telegram\".level",
                        "ERROR");
            }
        }
}
