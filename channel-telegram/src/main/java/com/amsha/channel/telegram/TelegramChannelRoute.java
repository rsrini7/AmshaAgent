package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.net.SocketException;

import com.amsha.common.util.LogUtil;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.TelegramComponent;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class TelegramChannelRoute extends RouteBuilder {

    private static final Logger log = Logger.getLogger(TelegramChannelRoute.class);

    @Inject
    CamelContext camelContext;

    @ConfigProperty(name = "amsha.channel.telegram.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "amsha.channel.telegram.bot-token")
    String botToken;

    @Override
    public void configure() throws Exception {
        log.infof(
                "Telegram Route configuring (enabled=%b, tokenPresent=%b)",
                enabled, botToken != null && !botToken.isBlank());
        if (!enabled) {
            log.info("Telegram channel disabled - skipping route");
            return;
        }

        // Fail fast if token is clearly a placeholder - don't let Camel hang
        if (botToken == null
                || botToken.isBlank()
                || botToken.startsWith("mock")
                || botToken.equals("mock-telegram-token")) {
            log.warn("Telegram bot token is a placeholder - skipping route registration.");
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

        from("telegram:bots?timeout=10&backoffErrorThreshold=3&backoffMultiplier=2")
                .routeId("telegram-bot")
                .onException(RuntimeCamelException.class)
                .onWhen(new isSocketException())
                .process(new logTransientError())
                .handled(true)
                .maximumRedeliveries(0)
                .end()
                .choice()
                .when(body().isInstanceOf(IncomingCallbackQuery.class))
                .bean(TelegramCallbackHandler.class, "handleCallback")
                .otherwise()
                .bean(TelegramAgentHandler.class, "receiveMessage")
                .end();
    }

    void onStart(@Observes StartupEvent ev) {
        log.infof("Telegram Route onStart (enabled=%b, contextStatus=%s)", enabled, camelContext.getStatus());
        if (!enabled || isPlaceholderToken()) return;

        try {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) {
                camelContext
                        .getLifecycleStrategies()
                        .removeIf(s -> s.getClass().getName().contains("JmxManagement"));
            }

            if (camelContext.getStatus().isStopped()) {
                log.info("Starting CamelContext manually...");
                camelContext.start();
            }

            if (camelContext.getRoute("telegram-bot") == null) {
                log.info("Registering Telegram route...");
                // ← DO NOT wrap in Thread/VirtualThread/lambda - must run on this CDI thread
                camelContext.addRoutes(this);
                camelContext.getRouteController().startRoute("telegram-bot");
                log.infof(
                        "Telegram route started. Status=%s",
                        camelContext.getRouteController().getRouteStatus("telegram-bot"));
            }
        } catch (Exception e) {
            log.error("Telegram route registration failed", e);
        }
    }

    private boolean isPlaceholderToken() {
        return botToken == null
                || botToken.isBlank()
                || botToken.startsWith("mock")
                || botToken.equals("mock-telegram-token");
    }

    /** Matches only when the root cause is a transient SocketException (connection reset, etc.) */
    private static class isSocketException implements Predicate {
        @Override
        public boolean matches(Exchange exchange) {
            Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
            while (cause != null && cause.getCause() != null && cause != cause.getCause()) {
                cause = cause.getCause();
            }
            return cause instanceof SocketException;
        }
    }

    /** Logs a single WARN line instead of a full stack trace for transient poll errors */
    private class logTransientError implements Processor {
        @Override
        public void process(Exchange exchange) {
            Throwable ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
            log.warnf("Telegram poll transient error: %s — will retry", LogUtil.rootMessage(ex));
        }
    }
}
