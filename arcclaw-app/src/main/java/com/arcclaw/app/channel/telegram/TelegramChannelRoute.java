package com.arcclaw.app.channel.telegram;

import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TelegramChannelRoute extends RouteBuilder {

    @ConfigProperty(name = "arcclaw.channel.telegram.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token", defaultValue = "mock-token")
    String botToken;

    @Override
    public void configure() throws Exception {
        if (!enabled) {
            return;
        }
        if (botToken == null || botToken.isBlank() || "mock-token".equals(botToken)) {
            // In tests or if not configured, we might want to skip or use a mock.
            // But for the sake of the testRouteExists, we MUST register it if it's
            // "mock-token".
            // Actually, the user says "Since bot-token has a default value, the route
            // should initialize".
        }

        // We explicitly append the botToken to the URI rather than relying on Camel's
        // internal {{property}} placeholder syntax, as requested algorithmically in the
        // prompt constraints.
        from("telegram:bots?authorizationToken=" + botToken)
                .bean(TelegramAgentHandler.class, "receiveMessage");
    }
}
