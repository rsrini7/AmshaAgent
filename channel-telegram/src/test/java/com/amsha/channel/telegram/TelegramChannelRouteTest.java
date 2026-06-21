package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.builder.AdviceWith;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(TelegramChannelRouteTest.TelegramEnabledProfile.class)
public class TelegramChannelRouteTest {

    @Inject
    CamelContext context;

    public static class TelegramEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "amsha.channel.telegram.enabled", "true",
                    "camel.component.telegram.authorization-token", "mock-token");
        }
    }

    @Test
    void testTelegramRoute() throws Exception {
        AdviceWith.adviceWith(context, "telegram-bot", a -> {
            a.replaceFromWith("direct:simulate-telegram-in");
            a.mockEndpointsAndSkip("telegram:*");
        });

        assertNotNull(context.getRoute("telegram-bot"), "Route 'telegram-bot' not found");
    }

    @Test
    void testRouteDoesNotHaveTokenInUri() throws Exception {
        AdviceWith.adviceWith(context, "telegram-bot", a -> {
            a.replaceFromWith("direct:simulate-telegram-in");
            a.mockEndpointsAndSkip("telegram:*");
        });

        Route route = context.getRoute("telegram-bot");
        assertNotNull(route, "Route 'telegram-bot' must be registered");
        String uri = route.getEndpoint().getEndpointUri();
        assertFalse(uri.contains("authorizationToken"), "Bot token must not appear in the Camel route URI");
        assertFalse(uri.contains("bot-token"), "Bot token config key must not appear in the Camel route URI");
    }
}
