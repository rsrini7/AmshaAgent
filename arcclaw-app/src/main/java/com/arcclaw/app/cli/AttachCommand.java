package com.arcclaw.app.cli;

import io.quarkus.runtime.Quarkus;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import com.arcclaw.app.channel.telegram.TelegramChannelRoute;
import picocli.CommandLine;

@CommandLine.Command(name = "attach", description = "Keep ArcClaw running as a background daemon for channel adapters")
public class AttachCommand implements Runnable {

    @CommandLine.Parameters(paramLabel = "<channel>", description = "Channel to service: telegram, discord")
    String channel;

    @Inject
    CamelContext camelContext;

    @Inject
    TelegramChannelRoute telegramChannelRoute;

    @Override
    public void run() {
        switch (channel.toLowerCase()) {
            case "telegram" -> {
                System.out.println("🔗 ArcClaw daemon running for Telegram.");
                try {
                    if (camelContext.getRoute("telegram-bot") == null) {
                        camelContext.addRoutes(telegramChannelRoute);
                        // Only start if the route was actually added (skipped if token is placeholder)
                        if (camelContext.getRoute("telegram-bot") != null) {
                            camelContext.getRouteController().startRoute("telegram-bot");
                            System.out.println("   Telegram route registered and started.");
                        } else {
                            System.out.println("   Telegram route registration skipped (likely due to missing token).");
                        }
                    } else {
                        System.out.println("   Telegram route already active.");
                    }
                } catch (Exception e) {
                    System.err.println("❌ Failed to register Telegram route: " + e.getMessage());
                }
                System.out.println("   Press Ctrl+C to shutdown.");
                Quarkus.waitForExit();
            }
            case "discord" -> {
                System.out.println("🔗 ArcClaw not supported Discord Yet.");
                System.out.println("   Press Ctrl+C to shutdown.");
            }
            default -> {
                System.err.println("❌ Unsupported channel: " + channel);
                System.err.println("   Supported: telegram");
            }
        }
    }
}
