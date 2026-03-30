package com.arcclaw.channel.web;

import java.time.Instant;
import java.util.UUID;
import java.io.StringReader;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

@WebSocket(path = "/ws/chat")
public class WebChatEndpoint {

    private static final Logger log = Logger.getLogger(WebChatEndpoint.class);
    
    // Fallback for TypedKey factory ambiguity — maps conn.id() -> arcclaw sessionId.
    private final Map<String, String> connectionToSession = new ConcurrentHashMap<>();

    @Inject WebSocketSessionStore sessionStore;
    @Inject MessagePipeline messagePipeline;
    @Inject WorkspaceManager workspaceManager;
    @Inject ManagedExecutor executor;

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        String sessionId = UUID.randomUUID().toString();
        connectionToSession.put(conn.id(), sessionId);
        sessionStore.register(sessionId, conn);
        conn.sendText(buildConnectedJson(sessionId)).subscribe().with(
            id -> {},
            err -> log.warnf("Failed to send connected event to %s: %s", conn.id(), err.getMessage())
        );
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection conn) {
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject json = reader.readObject();
            if (!"message".equals(json.getString("type", ""))) {
                return;
            }
            String text = json.getString("text", "");
            String sessionId = connectionToSession.get(conn.id());
            if (sessionId == null) {
                log.warn("Received message without a sessionId mapped for connection " + conn.id());
                return;
            }

            ChannelUser user = new ChannelUser(
                sessionId,
                sessionId,
                "Web User",
                ChannelType.WEBSOCKET
            );

            ChannelMessage.WebSocketMessage msg = new ChannelMessage.WebSocketMessage(
                user,
                text,
                sessionId,
                Instant.now()
            );

            MessageContext context = new MessageContext(sessionId, user, msg);
            
            // ManagedExecutor propagates CDI context (§78-1)
            executor.runAsync(() -> messagePipeline.process(context));

        } catch (Exception e) {
            log.warnf(e, "Failed to process incoming WebSocket message: %s", message);
        }
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        String sessionId = connectionToSession.remove(conn.id());
        if (sessionId != null) {
            sessionStore.unregister(sessionId);
            workspaceManager.terminateSession(sessionId);
        }
    }

    @OnError
    public void onError(WebSocketConnection conn, Throwable t) {
        String sessionId = connectionToSession.get(conn.id());
        log.errorf(t, "WebSocket error for session %s (connection %s)", sessionId, conn.id());
        if (sessionId != null) {
            connectionToSession.remove(conn.id());
            sessionStore.unregister(sessionId);
        }
    }

    private String buildConnectedJson(String sessionId) {
        return Json.createObjectBuilder()
            .add("type", "connected")
            .add("sessionId", sessionId)
            .build()
            .toString();
    }
}
