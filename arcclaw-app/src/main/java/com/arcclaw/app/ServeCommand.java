package com.arcclaw.app;

import io.quarkus.runtime.Quarkus;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import com.arcclaw.app.channel.telegram.TelegramChannelRoute;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import picocli.CommandLine.Command;

/**
 * Daemon / REST API mode — starts Quarkus HTTP server and blocks until
 * shutdown signal.
 * <p>
 * Usage: {@code arcclaw serve}
 */
@Command(name = "serve", description = "Start ArcClaw in daemon/REST API mode")
public class ServeCommand implements Runnable {

    private static final Logger log = Logger.getLogger(ServeCommand.class);

    @Inject
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    @Inject
    CamelContext camelContext;

    @Inject
    TelegramChannelRoute telegramChannelRoute;

    @Override
    public void run() {
        log.infof("ArcClaw daemon mode starting on port %d...", httpPort);
        try {
            if (camelContext.getRoute("telegram-bot") == null) {
                camelContext.addRoutes(telegramChannelRoute);
                // Only start if the route was actually added (skipped if token is placeholder)
                if (camelContext.getRoute("telegram-bot") != null) {
                    camelContext.getRouteController().startRoute("telegram-bot");
                    log.info("Telegram route registered and started.");
                } else {
                    log.info("Telegram route registration skipped (likely due to missing token).");
                }
            } else {
                log.info("Telegram route already active.");
            }
        } catch (Exception e) {
            log.errorf("Failed to register Telegram route: %s", e.getMessage());
        }
        Quarkus.waitForExit();
    }
}
