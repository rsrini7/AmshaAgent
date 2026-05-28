package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;

import com.arcclaw.channel.api.ChannelJobNotifier;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.common.util.LogUtil;

import org.jboss.logging.Logger;

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

        sendToSession(recipient, payload);
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

        sendToSession(recipient, payload);
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

        sendToSession(recipient, payload);
    }

    private void sendToSession(String sessionId, String payload) {
        sessionStore
                .get(sessionId)
                .ifPresentOrElse(
                        conn -> {
                            conn.sendText(payload)
                                    .subscribe()
                                    .with(
                                            id -> {},
                                            err -> log.warnf(
                                                    "Failed to send to session %s: %s",
                                                    sessionId, LogUtil.rootMessage(err)));
                        },
                        () -> log.warnf("WebJobNotifier: no WS session for id=%s", sessionId));
    }
}
