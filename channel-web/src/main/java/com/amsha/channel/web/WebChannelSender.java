package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;

import java.time.Duration;
import java.util.List;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ReplyNode;
import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

import io.quarkus.websockets.next.WebSocketConnection;

@ApplicationScoped
public class WebChannelSender implements ChannelSender {

    private static final Logger log = Logger.getLogger(WebChannelSender.class);

    @Inject
    WebSocketSessionStore sessionStore;

    @Inject
    WebReplyRenderer renderer;

    @Inject
    HtmlSanitizerService htmlSanitizer;

    @Override
    public ChannelType supports() {
        return ChannelType.WEBSOCKET;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        String rendered = renderer.render(nodes);
        String sanitized = htmlSanitizer.sanitize(rendered);

        ApprovalPrompt prompt = (ApprovalPrompt) nodes.stream()
                .filter(n -> n instanceof ApprovalPrompt)
                .findFirst()
                .orElse(null);

        sendRawWithSession(recipient, sanitized, prompt);
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        String sanitized = htmlSanitizer.sanitize(text);
        sendRawWithSession(recipient, sanitized, null);
    }

    private void sendRawWithSession(ChannelUser recipient, String text, ApprovalPrompt prompt) {
        String payload = buildMessageJson(text, prompt);
        // WebSocket ChannelUser.platformId is the durable owner after authentication.
        // The active socket is addressed by the channel-specific browser platform ID.
        String platformId = recipient.channelId();

        sessionStore
                .get(platformId)
                .ifPresentOrElse(
                        conn -> {
                            try {
                                conn.sendText(payload).await().atMost(Duration.ofSeconds(5));
                            } catch (Exception e) {
                                log.warnf("Failed to send to session %s: %s", platformId, LogUtil.rootMessage(e));
                                sessionStore.unregister(platformId);
                            }
                        },
                        () -> {
                            // If the direct platformId is stale (e.g. refresh), try a broadcast based on the sessionId
                            // that is known to exist for the user's platform reference (§85-C)
                            List<WebSocketConnection> ownerConnections =
                                    sessionStore.getConnectionsByOwner(recipient.platformId());
                            if (!ownerConnections.isEmpty()) {
                                sendToConnections(ownerConnections, payload, recipient.platformId());
                                return;
                            }

                            sessionStore
                                    .getAmshaSessionId(platformId)
                                    .ifPresentOrElse(
                                            sid -> sendBySessionWithTarget(sid, text, prompt),
                                            () -> log.warnf("WebChannelSender: no WS session for id=%s", platformId));
                        });
    }

    @Override
    public void sendBySession(String sessionId, String text) {
        String sanitized = htmlSanitizer.sanitize(text);
        sendBySessionWithTarget(sessionId, sanitized, null);
    }

    private void sendBySessionWithTarget(String sessionId, String text, ApprovalPrompt prompt) {
        String payload = buildMessageJson(text, prompt);
        List<WebSocketConnection> conns = sessionStore.getConnectionsByAmshaSession(sessionId);

        if (!conns.isEmpty()) {
            conns.forEach(conn -> {
                try {
                    conn.sendText(payload)
                            .subscribe()
                            .with(
                                    v -> {},
                                    err -> log.warnf(
                                            "Failed to push result to session %s conn %s: %s",
                                            sessionId, conn.id(), err.getMessage()));
                } catch (Exception e) {
                    log.warnf("Exception pushing result to session %s: %s", sessionId, LogUtil.rootMessage(e));
                }
            });
            log.infof("Broadcast result for session %s to %d connection(s)", sessionId, conns.size());
        } else {
            log.warnf("WebChannelSender: no active connections found for session %s (async result dropped)", sessionId);
        }
    }

    private void sendToConnections(List<WebSocketConnection> connections, String payload, String targetId) {
        connections.forEach(conn -> conn.sendText(payload)
                .subscribe()
                .with(
                        _ -> {},
                        err -> log.warnf(
                                "Failed push result target %s conn %s: %s", targetId, conn.id(), err.getMessage())));
    }

    private String buildMessageJson(String text, ApprovalPrompt prompt) {
        var builder = Json.createObjectBuilder()
                .add("type", "message")
                .add("role", "assistant")
                .add("text", text)
                .add("timestamp", System.currentTimeMillis());

        if (prompt != null) {
            builder.add("sessionId", prompt.sessionId());
            builder.add(
                    "approval",
                    Json.createObjectBuilder()
                            .add("tool", prompt.toolName())
                            .add("params", prompt.paramsJson() != null ? prompt.paramsJson() : "{}"));
        }

        return builder.build().toString();
    }
}
