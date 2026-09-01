package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.Unremovable;
import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;

/**
 * Configures Vert.x HTTP server options to allow WebSocket audio/media frames up to 36 MB,
 * preventing Netty CorruptedWebSocketFrameException (default 65536 byte limit) while safely
 * accommodating 25 MB binary attachments with Base64 encoding overhead.
 */
@ApplicationScoped
@Unremovable
public class WebsocketServerCustomizer implements HttpServerOptionsCustomizer {

    private static final int MAX_WEBSOCKET_PAYLOAD_BYTES = 37_748_736; // 36 MB

    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        if (options != null) {
            options.setMaxWebSocketFrameSize(MAX_WEBSOCKET_PAYLOAD_BYTES);
            options.setMaxWebSocketMessageSize(MAX_WEBSOCKET_PAYLOAD_BYTES);
        }
    }

    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        if (options != null) {
            options.setMaxWebSocketFrameSize(MAX_WEBSOCKET_PAYLOAD_BYTES);
            options.setMaxWebSocketMessageSize(MAX_WEBSOCKET_PAYLOAD_BYTES);
        }
    }
}
