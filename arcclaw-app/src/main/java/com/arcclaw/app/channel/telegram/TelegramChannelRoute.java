package com.arcclaw.app.channel.telegram;

import org.apache.camel.builder.RouteBuilder;
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

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token", defaultValue = "mock-token")
    String botToken;

    @Override
    public void configure() throws Exception {
        if (!enabled) {
            return;
        }

        if ("mock-token".equals(botToken)) {
            log.warn("Telegram Bot Token is set to 'mock-token'. The bot will not function correctly. Please set TELEGRAM_BOT_TOKEN in .env file.");
        }
        
        String displayToken = (botToken != null && botToken.length() > 10) 
            ? botToken.substring(0, 5) + "..." 
            : botToken;
        log.info("Telegram Route initializing with token: " + displayToken);

        from("telegram:bots?authorizationToken=" + botToken)
                .routeId("telegram-bot")
                .bean(telegramAgentHandler, "receiveMessage");
    }
}
