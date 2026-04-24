package com.arcclaw.channel.web;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import io.quarkus.websockets.next.WebSocketConnection;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WebChannelSender implements ChannelSender {

    private static final Logger log = Logger.getLogger(WebChannelSender.class);

    @Inject WebSocketSessionStore sessionStore;
    @Inject WebReplyRenderer renderer;

    @Override
    public ChannelType supports() {
        return ChannelType.WEBSOCKET;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        String rendered = renderer.render(nodes);
        
        String targetSessionId = nodes.stream()
            .filter(n -> n instanceof ReplyNode.ApprovalPrompt)
            .map(n -> ((ReplyNode.ApprovalPrompt) n).sessionId())
            .findFirst()
            .orElse(null);

        sendRawWithSession(recipient, rendered, targetSessionId);
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        sendRawWithSession(recipient, text, null);
    }

    private void sendRawWithSession(ChannelUser recipient, String text, String targetSessionId) {
        String payload = buildMessageJson(text, targetSessionId);
        String platformId = recipient.platformId();

        sessionStore.get(platformId).ifPresentOrElse(
            conn -> {
                try {
                    conn.sendText(payload).await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.warnf("Failed to send to session %s: %s", platformId, e.getMessage());
                    sessionStore.unregister(platformId);
                }
            },
            () -> {
                // If the direct platformId is stale (e.g. refresh), try a broadcast based on the sessionId
                // that is known to exist for the user's platform reference (§85-C)
                sessionStore.getArcclawSessionId(platformId).ifPresentOrElse(
                    sid -> sendBySessionWithTarget(sid, text, targetSessionId),
                    () -> log.warnf("WebChannelSender: no WS session for id=%s", platformId)
                );
            }
        );
    }

    @Override
    public void sendBySession(String sessionId, String text) {
        sendBySessionWithTarget(sessionId, text, null);
    }

    private void sendBySessionWithTarget(String sessionId, String text, String targetSessionId) {
        String payload = buildMessageJson(text, targetSessionId);
        List<WebSocketConnection> conns = sessionStore.getConnectionsByArcclawSession(sessionId);
        
        if (!conns.isEmpty()) {
            conns.forEach(conn -> {
                try {
                    conn.sendText(payload).subscribe().with(
                        v -> {},
                        err -> log.warnf("Failed to push result to session %s conn %s: %s",
                                         sessionId, conn.id(), err.getMessage())
                    );
                } catch (Exception e) {
                    log.warnf("Exception pushing result to session %s: %s", sessionId, e.getMessage());
                }
            });
            log.infof("Broadcast result for session %s to %d connection(s)", sessionId, conns.size());
        } else {
            log.warnf("WebChannelSender: no active connections found for session %s (async result dropped)", sessionId);
        }
    }

    private String buildMessageJson(String text, String targetSessionId) {
        var builder = Json.createObjectBuilder()
            .add("type", "message")
            .add("role", "assistant")
            .add("text", text)
            .add("timestamp", System.currentTimeMillis());
        
        if (targetSessionId != null) {
            builder.add("sessionId", targetSessionId);
        }
        
        return builder.build().toString();
    }
}
