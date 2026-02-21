package com.arcclaw.app;

import com.arcclaw.app.channel.telegram.TelegramChannelRoute;
import io.quarkus.runtime.Quarkus;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
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
        
        if (camelContext != null) {
            log.infof("Camel Context Status: %s", camelContext.getStatus());
            
            try {
                if (camelContext.getRoute("telegram-bot") == null) {
                    log.info("Telegram route not found. Manually adding TelegramChannelRoute...");
                    camelContext.addRoutes(telegramChannelRoute);
                }
            } catch (Exception e) {
                log.error("Failed to add TelegramChannelRoute", e);
            }

            if (camelContext.isStopped()) {
                log.info("Starting Camel Context...");
                camelContext.start();
                log.info("Camel Context started.");
            }
            
            log.infof("Camel Routes: %d", camelContext.getRoutes().size());
            camelContext.getRoutes().forEach(route -> {
                log.infof("Route: %s, Status: %s", route.getRouteId(), route.getUptime());
            });
        } else {
            log.error("Camel Context is NULL!");
        }

        Quarkus.waitForExit();
    }
}
