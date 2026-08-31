package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;

import java.time.Duration;
import java.util.Base64;
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

    @Override
    public boolean sendAudio(ChannelUser recipient, byte[] audio, String mimeType, String fileName) {
        if (recipient == null || audio == null || audio.length == 0) {
            return false;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        String payload = buildAudioMessageJson(base64Audio, mimeType, fileName);
        return sendPayloadToRecipient(recipient, payload);
    }

    private void sendRawWithSession(ChannelUser recipient, String text, ApprovalPrompt prompt) {
        String payload = buildMessageJson(text, prompt);
        sendPayloadToRecipient(recipient, payload);
    }

    private boolean sendPayloadToRecipient(ChannelUser recipient, String payload) {
        // WebSocket ChannelUser.platformId is the durable owner after authentication.
        // The active socket is addressed by the channel-specific browser platform ID.
        String platformId = recipient.channelId();

        var directConn = sessionStore.get(platformId);
        if (directConn.isPresent()) {
            try {
                directConn.get().sendText(payload).await().atMost(Duration.ofSeconds(5));
                return true;
            } catch (Exception e) {
                log.warnf("Failed to send to session %s: %s", platformId, LogUtil.rootMessage(e));
                sessionStore.unregister(platformId);
                return false;
            }
        }

        // If the direct platformId is stale (e.g. refresh), try a broadcast based on the sessionId
        // that is known to exist for the user's platform reference (§85-C)
        List<WebSocketConnection> ownerConnections = sessionStore.getConnectionsByOwner(recipient.platformId());
        if (!ownerConnections.isEmpty()) {
            sendToConnections(ownerConnections, payload, recipient.platformId());
            return true;
        }

        var amshaSessionId = sessionStore.getAmshaSessionId(platformId);
        if (amshaSessionId.isPresent()) {
            return sendPayloadToAmshaSession(amshaSessionId.get(), payload);
        }

        log.warnf("WebChannelSender: no WS session for id=%s", platformId);
        return false;
    }

    @Override
    public void sendBySession(String sessionId, String text) {
        String sanitized = htmlSanitizer.sanitize(text);
        sendBySessionWithTarget(sessionId, sanitized, null);
    }

    private void sendBySessionWithTarget(String sessionId, String text, ApprovalPrompt prompt) {
        String payload = buildMessageJson(text, prompt);
        sendPayloadToAmshaSession(sessionId, payload);
    }

    private boolean sendPayloadToAmshaSession(String sessionId, String payload) {
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
                                            sessionId, conn.id(), LogUtil.rootMessage(err)));
                } catch (Exception e) {
                    log.warnf("Exception pushing result to session %s: %s", sessionId, LogUtil.rootMessage(e));
                }
            });
            log.infof("Broadcast result for session %s to %d connection(s)", sessionId, conns.size());
            return true;
        } else {
            log.warnf("WebChannelSender: no active connections found for session %s (async result dropped)", sessionId);
            return false;
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

    private static final int MAX_FILE_NAME_CHARS = 120;

    private static String safeFileName(String fileName, String mimeType) {
        String candidate = fileName == null ? "" : fileName.replace('\\', '/');
        int separator = candidate.lastIndexOf('/');
        if (separator >= 0) {
            candidate = candidate.substring(separator + 1);
        }
        candidate = candidate
                .codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .trim();
        if (candidate.isBlank()) {
            candidate = switch (mimeType != null ? mimeType : "") {
                case "audio/mpeg" -> "amsha-reply.mp3";
                case "audio/ogg" -> "amsha-reply.ogg";
                case "audio/webm" -> "amsha-reply.webm";
                default -> "amsha-reply.wav";
            };
        }
        return candidate.length() <= MAX_FILE_NAME_CHARS ? candidate : candidate.substring(0, MAX_FILE_NAME_CHARS);
    }

    private String buildAudioMessageJson(String base64Audio, String mimeType, String fileName) {
        String safeMime = mimeType != null && !mimeType.isBlank() ? mimeType.trim() : "audio/wav";
        String safeName = safeFileName(fileName, safeMime);
        return Json.createObjectBuilder()
                .add("type", "message")
                .add("role", "assistant")
                .add("text", "")
                .add(
                        "audio",
                        Json.createObjectBuilder()
                                .add("mimeType", safeMime)
                                .add("fileName", safeName)
                                .add("data", base64Audio))
                .add("timestamp", System.currentTimeMillis())
                .build()
                .toString();
    }
}
