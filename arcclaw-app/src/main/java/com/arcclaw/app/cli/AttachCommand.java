package com.arcclaw.app.cli;

import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.ChannelDaemon;
import picocli.CommandLine;

/**
 * Attach command — keeps ArcClaw running as a background daemon for a specific channel.
 * <p>
 * Telegram route lifecycle is now managed by TelegramChannelRoute.onStart()
 * which self-registers the route via the Quarkus StartupEvent observer.
 */
@CommandLine.Command(name = "attach", description = "Keep ArcClaw running as a background daemon for channel adapters")
public class AttachCommand implements Runnable {

    @CommandLine.Parameters(paramLabel = "<channel>", description = "Channel to service: telegram, discord")
    String channel;

    @Inject
    @Any
    Instance<ChannelDaemon> daemons;

    @Override
    public void run() {
        String target = channel.toLowerCase();
        Optional<ChannelDaemon> daemon = daemons.stream()
            .filter(d -> d.channelId().equalsIgnoreCase(target))
            .findFirst();

        if (daemon.isEmpty()) {
            System.out.printf("❌ Unknown channel '%s'. Available: %s%n",
                channel,
                daemons.stream().map(ChannelDaemon::channelId).collect(Collectors.joining(", ")));
            return;
        }

        if (!daemon.get().isConfigured()) {
            System.out.printf("⚠️ Channel '%s' is not configured. Check required environment variables.%n", channel);
            return;
        }

        try {
            System.out.printf("🔗 ArcClaw daemon starting for %s...%n", channel);
            System.out.println("   Press Ctrl+C to shutdown.");
            daemon.get().start();
        } catch (Exception e) {
            System.err.printf("❌ Channel daemon '%s' failed: %s%n", channel, e.getMessage());
        }
    }
}
