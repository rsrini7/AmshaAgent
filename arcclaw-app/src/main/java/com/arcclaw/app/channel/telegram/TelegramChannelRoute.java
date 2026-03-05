package com.arcclaw.app.channel.telegram;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.TelegramComponent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TelegramChannelRoute extends RouteBuilder {

    private static final Logger log = Logger.getLogger(TelegramChannelRoute.class);

    @Inject
    TelegramAgentHandler telegramAgentHandler;

    @ConfigProperty(name = "arcclaw.channel.telegram.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    String botToken;

    @Override
    public void configure() throws Exception {
        if (!enabled) {
            return;
        }

        // Wire token directly onto the component version-agnostic, never in URI
        TelegramComponent telegram = new TelegramComponent();
        telegram.setAuthorizationToken(botToken);
        getContext().addComponent("telegram", telegram);

        log.info("Telegram Route initializing");

        from("telegram:bots")
                .routeId("telegram-bot")
                .bean(telegramAgentHandler, "receiveMessage");
    }
}
