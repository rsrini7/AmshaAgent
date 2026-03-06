package com.arcclaw.app.cli;

import io.quarkus.runtime.Quarkus;
import picocli.CommandLine;

@CommandLine.Command(name = "attach", description = "Keep ArcClaw running as a background daemon for channel adapters")
public class AttachCommand implements Runnable {

    @CommandLine.Parameters(paramLabel = "<channel>", description = "Channel to service: telegram, discord")
    String channel;

    @Override
    public void run() {
        switch (channel.toLowerCase()) {
            case "telegram" -> {
                System.out.println("🔗 ArcClaw daemon running for Telegram.");
                System.out.println("🔗 Telegram route managed by Camel Quarkus. " +
                        "If TELEGRAM_BOT_TOKEN is set, the route is already active.");
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
