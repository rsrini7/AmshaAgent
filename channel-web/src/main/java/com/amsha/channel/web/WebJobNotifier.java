package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;

import java.util.LinkedHashSet;
import java.util.Set;

import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

import io.quarkus.websockets.next.WebSocketConnection;

@ApplicationScoped
public class WebJobNotifier implements ChannelJobNotifier {

    private static final Logger log = Logger.getLogger(WebJobNotifier.class);

    @Inject
    WebSocketSessionStore sessionStore;

    @Override
    public boolean supports(ChannelType channelType) {
        return ChannelType.WEBSOCKET.equals(channelType);
    }

    @Override
    public void notifyApprovalRequired(
            String recipient, String cronSessionId, String toolName, String renderedMessage) {
        String payload = Json.createObjectBuilder()
                .add("type", "approval_required")
                .add("role", "assistant")
                .add("text", renderedMessage)
                .add("sessionId", cronSessionId)
                .add("toolName", toolName)
                .add("instructions", "To approve, run /approve " + cronSessionId)
                .build()
                .toString();

        sendToSession(recipient, cronSessionId, payload);
    }

    @Override
    public void notifyApprovalRequired(
            String recipient, String cronSessionId, String toolName, String renderedMessage, String displayParamsJson) {
        var payloadBuilder = Json.createObjectBuilder()
                .add("type", "approval_required")
                .add("role", "assistant")
                .add("text", renderedMessage)
                .add("sessionId", cronSessionId)
                .add("toolName", toolName)
                .add("instructions", "To approve, run /approve " + cronSessionId);
        if (displayParamsJson != null && !displayParamsJson.isBlank()) {
            payloadBuilder.add(
                    "approval", Json.createObjectBuilder().add("tool", toolName).add("params", displayParamsJson));
        }
        sendToSession(recipient, cronSessionId, payloadBuilder.build().toString());
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        String payload = Json.createObjectBuilder()
                .add("type", "message")
                .add("role", "assistant")
                .add("text", renderedMessage)
                .add("sessionId", sessionId)
                .build()
                .toString();

        sendToSession(recipient, sessionId, payload);
    }

    @Override
    public void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes) {
        String msg = String.format(
                "⏱ Approval timeout: tool '%s' was auto-denied after %d minutes.", pendingToolName, timeoutMinutes);

        String payload = Json.createObjectBuilder()
                .add("type", "message")
                .add("role", "assistant")
                .add("text", msg)
                .add("sessionId", sessionId)
                .build()
                .toString();

        sendToSession(recipient, sessionId, payload);
    }

    private void sendToSession(String recipient, String relatedSessionId, String payload) {
        Set<WebSocketConnection> targets = new LinkedHashSet<>();
        if (recipient != null && !recipient.isBlank()) {
            sessionStore.get(recipient).ifPresent(targets::add);
        }
        targets.addAll(sessionStore.getConnectionsByOwner(recipient));
        targets.addAll(sessionStore.getConnectionsByAmshaSession(relatedSessionId));

        if (targets.isEmpty()) {
            log.warnf(
                    "WebJobNotifier: no active WS connection for recipient=%s relatedSession=%s",
                    recipient, relatedSessionId);
            return;
        }

        for (WebSocketConnection conn : targets) {
            conn.sendText(payload)
                    .subscribe()
                    .with(
                            _ -> {},
                            err -> log.warnf(
                                    "Failed to send WS notification recipient=%s connection=%s: %s",
                                    recipient, conn.id(), LogUtil.rootMessage(err)));
        }
    }
}
