package com.arcclaw.app;

import jakarta.inject.Inject;

import io.quarkus.runtime.Quarkus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import picocli.CommandLine.Command;

/**
 * Daemon / REST API mode — starts Quarkus HTTP server and blocks until
 * shutdown signal.
 * <p>
 * Usage: {@code arcclaw serve}
 * <p>
 * Telegram route lifecycle is now managed by TelegramChannelRoute.onStart()
 * which self-registers the route via the Quarkus StartupEvent observer.
 */
@Command(name = "serve", description = "Start ArcClaw in daemon/REST API mode")
public class ServeCommand implements Runnable {

    private static final Logger log = Logger.getLogger(ServeCommand.class);

    @Inject
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @Override
    public void run() {
        log.infof("ArcClaw daemon mode starting on port %d...", httpPort);
        log.info("Telegram route lifecycle managed by TelegramChannelRoute.onStart().");
        Quarkus.waitForExit();
    }
}
