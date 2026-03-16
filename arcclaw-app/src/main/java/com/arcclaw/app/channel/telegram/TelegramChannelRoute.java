package com.arcclaw.app.channel.telegram;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.TelegramComponent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TelegramChannelRoute extends RouteBuilder {

    private static final Logger log = Logger.getLogger(TelegramChannelRoute.class);

    @Inject
    TelegramAgentHandler telegramAgentHandler;

    @Inject
    CamelContext camelContext;

    @ConfigProperty(name = "arcclaw.channel.telegram.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    String botToken;

    @Override
    public void configure() throws Exception {
        log.infof("Telegram Route configuring (enabled=%b, tokenPresent=%b)", enabled, botToken != null && !botToken.isBlank());
        if (!enabled) {
            log.info("Telegram channel disabled — skipping route");
            return;
        }

        // Fail fast if token is clearly a placeholder — don't let Camel hang
        if (botToken == null || botToken.isBlank()
                || botToken.startsWith("mock")
                || botToken.equals("mock-telegram-token")) {
            log.warn("Telegram bot token is a placeholder — skipping route registration.");
            return;
        }

        // Wire token directly onto the component version-agnostic, never in URI
        TelegramComponent telegram = new TelegramComponent();
        telegram.setAuthorizationToken(botToken);
        // Use remove/add pattern or check if exists to avoid conflicts
        if (getContext().getComponent("telegram") != null) {
            getContext().removeComponent("telegram");
        }
        getContext().addComponent("telegram", telegram);

        log.info("Telegram Route initializing endpoint");

        from("telegram:bots")
                .routeId("telegram-bot")
                .bean(telegramAgentHandler, "receiveMessage");
    }

    void onStart(@Observes StartupEvent ev) {
        log.infof("Telegram Route onStart (enabled=%b, contextStatus=%s)", enabled, camelContext.getStatus());
        if (!enabled) return;
        if (isPlaceholderToken()) {
            log.warn("Telegram bot token is a placeholder — skipping self-registration.");
            return;
        }

        Thread.ofVirtual().name("telegram-route-registrar").start(() -> {
            try {
                if (camelContext.getStatus().isStopped()) {
                    // WHY removeIf instead of camelContext.disableJMX():
                    // disableJMX() is guarded by isNew() check in AbstractCamelContext.
                    // In Quarkus dev mode, StartupEvent fires after INITIALIZED state —
                    // disableJMX() silently no-ops, leaving JmxManagementLifecycleStrategy
                    // registered. Calling camelContext.start() then NPEs at
                    // JmxManagementLifecycleStrategy.onContextStarting():189.
                    // Direct removeIf bypasses the state guard. JAR/native unaffected
                    // since camelContext.getStatus().isStopped() is false there.
                    camelContext.getLifecycleStrategies()
                        .removeIf(s -> s.getClass().getName()
                            .contains("JmxManagement"));

                    log.info("Starting CamelContext manually...");
                    camelContext.start();
                }

                if (camelContext.getRoute("telegram-bot") == null) {
                    log.info("Registering Telegram route...");
                    camelContext.addRoutes(this);
                    camelContext.getRouteController().startRoute("telegram-bot");
                    log.infof("Telegram route started. Status=%s",
                        camelContext.getRouteController()
                            .getRouteStatus("telegram-bot"));
                } else {
                    log.info("Telegram route already registered — skipping.");
                }
            } catch (Exception e) {
                log.error("Telegram route registration failed", e);
            }
        });
    }
    
    private boolean isPlaceholderToken() {
        return botToken == null || botToken.isBlank()
            || botToken.startsWith("mock")
            || botToken.equals("mock-telegram-token");
    }
}
