package com.arcclaw.app.cli;

import io.quarkus.runtime.Quarkus;
import picocli.CommandLine;

import com.arcclaw.app.channel.telegram.TelegramChannelRoute;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;

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
                System.out.println(
                        "   Note: TelegramChannelRoute activates automatically");
                System.out.println(
                        "   when TELEGRAM_BOT_TOKEN is set in environment.");
                
                try {
                    if (camelContext.getRoute("telegram-bot") == null) {
                        camelContext.addRoutes(telegramChannelRoute);
                    }
                    if (camelContext.isStopped()) {
                        camelContext.start();
                    }
                } catch (Exception e) {
                    System.err.println("❌ Failed to start Telegram route: " + e.getMessage());
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
