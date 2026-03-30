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
        sendRaw(recipient, rendered);
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        String payload = Json.createObjectBuilder()
            .add("type", "message")
            .add("role", "assistant")
            .add("text", text)
            .build().toString();

        sessionStore.get(recipient.platformId()).ifPresentOrElse(
            conn -> {
                try {
                    conn.sendText(payload).await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.warnf("Failed to send to session %s: %s", recipient.platformId(), e.getMessage());
                    sessionStore.unregister(recipient.platformId());
                }
            },
            () -> log.warnf("WebChannelSender: no WS session for id=%s", recipient.platformId())
        );
    }
}
