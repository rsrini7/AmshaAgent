package com.arcclaw.app.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class TelegramChannelRoute extends RouteBuilder {

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    Optional<String> botToken;

    @Override
    public void configure() throws Exception {
        if (botToken.isEmpty() || botToken.get().isBlank()) {
            log.warn("Telegram bot token is not configured. Telegram route will not be started.");
            return;
        }

        // We explicitly append the botToken to the URI rather than relying on Camel's
        // internal {{property}} placeholder syntax, as requested algorithmically in the
        // prompt constraints.
        from("telegram:bots?authorizationToken=" + botToken.get())
                .bean(TelegramAgentHandler.class, "receiveMessage");
    }
}
