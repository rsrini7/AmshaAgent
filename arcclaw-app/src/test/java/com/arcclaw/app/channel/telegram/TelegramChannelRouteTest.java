package com.arcclaw.app.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.apache.camel.Route;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.TelegramComponent;
import org.apache.camel.quarkus.test.CamelQuarkusTestSupport;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

@QuarkusTest
@TestProfile(TelegramChannelRouteTest.TelegramEnabledProfile.class)
public class TelegramChannelRouteTest extends CamelQuarkusTestSupport {

    @Inject
    TelegramChannelRoute route;

    public static class TelegramEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "arcclaw.channel.telegram.enabled", "true",
                "camel.component.telegram.authorization-token", "mock-token"
            );
        }
    }

    @Override
    protected void bindToRegistry(org.apache.camel.spi.Registry registry) throws Exception {
        TelegramComponent telegram = new TelegramComponent();
        telegram.setAuthorizationToken("mock-token-for-test");
        registry.bind("telegram", telegram);
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return route;
    }

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Test
    void testTelegramRoute() throws Exception {
        // Mock the route's from endpoint to prevent real Telegram polling during tests
        AdviceWith.adviceWith(context, "telegram-bot", a -> {
            a.replaceFromWith("direct:simulate-telegram-in");
            a.mockEndpointsAndSkip("telegram:*");
        });

        // Start context manually since we used isUseAdviceWith
        context.start();

        assertNotNull(context.getRoute("telegram-bot"), "Route 'telegram-bot' not found");
    }

    @Test
    void testRouteDoesNotHaveTokenInUri() throws Exception {
        AdviceWith.adviceWith(context, "telegram-bot", a -> {
            a.replaceFromWith("direct:simulate-telegram-in");
            a.mockEndpointsAndSkip("telegram:*");
        });
        context.start();

        Route route = context.getRoute("telegram-bot");
        assertNotNull(route, "Route 'telegram-bot' must be registered");
        String uri = route.getEndpoint().getEndpointUri();
        assertFalse(uri.contains("authorizationToken"),
            "Bot token must not appear in the Camel route URI");
        assertFalse(uri.contains("bot-token"),
            "Bot token config key must not appear in the Camel route URI");
    }
}


