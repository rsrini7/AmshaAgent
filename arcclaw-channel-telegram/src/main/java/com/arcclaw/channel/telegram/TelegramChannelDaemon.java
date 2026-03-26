package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelDaemon;
import io.quarkus.runtime.Quarkus;
import org.apache.camel.CamelContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TelegramChannelDaemon implements ChannelDaemon {

    @Inject
    CamelContext camelContext;

    @Inject
    TelegramChannelRoute route;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token", defaultValue = "")
    String botToken;

    @Override
    public String channelId() {
        return "telegram";
    }

    @Override
    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank() && !botToken.contains("mock");
    }

    @Override
    public void start() throws Exception {
        // Ensure route is added to the context if not already
        if (camelContext.getRoute("telegram-bot") == null) {
            camelContext.addRoutes(route);
        }
        Quarkus.waitForExit();
    }
}
