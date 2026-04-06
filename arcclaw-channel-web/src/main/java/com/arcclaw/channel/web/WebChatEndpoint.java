package com.arcclaw.channel.web;

import java.io.StringReader;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import com.arcclaw.core.pipeline.SessionIdResolver;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

@WebSocket(path = "/ws/chat")
public class WebChatEndpoint {

    private static final Logger log = Logger.getLogger(WebChatEndpoint.class);

    // conn.id() → platformId (UUID per WebSocket connection, used as channelId/userId)
    private final Map<String, String> connectionToSession = new ConcurrentHashMap<>();

    // conn.id() → ArcClaw sessionId (resolved by SessionRegistry, used for REST/HITL calls)
    private final Map<String, String> connectionToArcclawSession = new ConcurrentHashMap<>();

    @Inject WebSocketSessionStore sessionStore;
    @Inject MessagePipeline messagePipeline;
    @Inject WorkspaceManager workspaceManager;
    @Inject ManagedExecutor executor;
    @Inject SessionIdResolver sessionIdResolver;
    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @ConfigProperty(name = "arcclaw.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalKey;

    @ConfigProperty(name = "arcclaw.api.keys.ci-pipeline", defaultValue = "key-ci-changeme")
    String ciKey;

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        if (requireAuth) {
            String apiKey = conn.handshakeRequest().header("X-ArcClaw-API-Key");
            // Some websockets pass auth via subprotocol if browser doesn't support custom headers
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = conn.handshakeRequest().header("Sec-WebSocket-Protocol");
            }
            if (!internalKey.equals(apiKey) && !ciKey.equals(apiKey)) {
                log.warnf("WebSocket rejected: invalid API key from connId=%s", conn.id());
                // We must send error then close
                conn.sendTextAndAwait("{\"type\":\"error\",\"text\":\"Invalid API key\"}");
                conn.close();
                return;
            }
        }

        // platformId is the stable per-connection identity (channelId + userId)
        String platformId = UUID.randomUUID().toString();
        connectionToSession.put(conn.id(), platformId);
        sessionStore.register(platformId, conn);

        // Resolve the ArcClaw sessionId eagerly so the UI has it from the first message.
        // This mirrors exactly what SessionResolutionHandler does on the first pipeline call.
        String arcclawSessionId = sessionIdResolver.resolveOrCreate(
            ChannelType.WEBSOCKET.id().toUpperCase(), platformId, platformId);
        connectionToArcclawSession.put(conn.id(), arcclawSessionId);

        conn.sendText(buildConnectedJson(platformId, arcclawSessionId))
            .subscribe().with(
                id -> {},
                err -> log.warnf("Failed to send connected event to %s: %s",
                    conn.id(), err.getMessage())
            );

        log.infof("WebSocket opened: connId=%s platformId=%s arcclawSession=%s",
            conn.id(), platformId, arcclawSessionId);
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection conn) {
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject json = reader.readObject();
            if (!"message".equals(json.getString("type", ""))) {
                return;
            }
            String text = json.getString("text", "");
            if (text.isBlank()) {
                return;
            }

            String platformId = connectionToSession.get(conn.id());
            if (platformId == null) {
                log.warn("Received message without a platformId mapped for connection " + conn.id());
                return;
            }

            ChannelUser user = new ChannelUser(
                platformId,
                platformId,
                "Web User",
                ChannelType.WEBSOCKET
            );

            ChannelMessage.WebSocketMessage msg = new ChannelMessage.WebSocketMessage(
                user,
                text,
                platformId,
                Instant.now()
            );

            MessageContext context = new MessageContext(platformId, user, msg);

            // ManagedExecutor propagates CDI context (§78-1)
            executor.runAsync(() -> messagePipeline.process(context));

        } catch (Exception e) {
            log.warnf(e, "Failed to process incoming WebSocket message: %s", message);
        }
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        String platformId = connectionToSession.remove(conn.id());
        String arcclawSessionId = connectionToArcclawSession.remove(conn.id());

        if (platformId != null) {
            sessionStore.unregister(platformId);
        }
        // Terminate using ArcClaw sessionId — not platformId (§78-fix-2)
        if (arcclawSessionId != null) {
            workspaceManager.terminateSession(arcclawSessionId);
            log.infof("WebSocket closed: connId=%s arcclawSession=%s terminated",
                conn.id(), arcclawSessionId);
        }
    }

    @OnError
    public void onError(WebSocketConnection conn, Throwable t) {
        String platformId = connectionToSession.get(conn.id());
        String arcclawSessionId = connectionToArcclawSession.get(conn.id());
        log.errorf(t, "WebSocket error: connId=%s platformId=%s arcclawSession=%s",
            conn.id(), platformId, arcclawSessionId);
        if (platformId != null) {
            connectionToSession.remove(conn.id());
            sessionStore.unregister(platformId);
        }
        if (arcclawSessionId != null) {
            connectionToArcclawSession.remove(conn.id());
        }
    }

    /**
     * Returns both platformId and arcclawSessionId in the connected event.
     * The UI MUST use arcclawSessionId for all REST calls (/api/v1/hitl, /api/v1/sessions/{id}/approve).
     * The UI MUST use platformId only as the local WebSocket reference if needed.
     */
    private String buildConnectedJson(String platformId, String arcclawSessionId) {
        return Json.createObjectBuilder()
            .add("type", "connected")
            .add("sessionId", arcclawSessionId)   // use this for REST/HITL calls
            .add("platformId", platformId)         // WebSocket connection reference
            .build()
            .toString();
    }
}
