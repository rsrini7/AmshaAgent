package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.hitl.PendingApprovalChecker;
import com.amsha.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;

@ApplicationScoped
@WebSocket(path = "/ws/chat")
public class WebChatEndpoint {

    private static final Logger log = Logger.getLogger(WebChatEndpoint.class);

    // Sessions currently undergoing termination (keyed by amshaSessionId)
    private final Set<String> terminatingSessions = ConcurrentHashMap.newKeySet();

    @Inject
    WebSocketSessionStore sessionStore;

    @Inject
    ChannelServices channelServices;

    @Inject
    PendingApprovalChecker pendingApprovalChecker;

    @Inject
    WsTokenStore wsTokenStore;

    private record ConnectionState(String platformId, String amshaSessionId, boolean authenticated) {}

    // conn.id() → consolidated connection state
    private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();

    @ConfigProperty(name = "amsha.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalKey;

    @ConfigProperty(name = "amsha.api.keys.ci", defaultValue = "key-ci-changeme")
    String ciKey;

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        log.infof("WebSocket handshake: connId=%s", conn.id());

        try {
            // platformId is the stable per-connection identity (channelId + userId)
            HandshakeRequest handshake = conn.handshakeRequest();
            String query = (handshake != null) ? handshake.query() : null;
            String platformId = null;

            if (query != null && query.contains("sessionId=")) {
                String[] parts = query.split("sessionId=", 2);
                if (parts.length > 1) {
                    platformId = URLDecoder.decode(parts[1].split("&", 2)[0], StandardCharsets.UTF_8);
                }
            }

            if (platformId == null || platformId.isBlank()) {
                log.warnf(
                        "No platformId (sessionId) found in query string for connId=%s. Generating random.", conn.id());
                platformId = UUID.randomUUID().toString();
            }

            String amshaSessionId = channelServices.resolveOrCreateSession(
                    ChannelType.WEBSOCKET.id().toUpperCase(Locale.ROOT), platformId, platformId);

            // Close any stale connection for the same platformId to prevent duplicate broadcasts
            String pid = platformId;
            sessionStore.get(pid).ifPresent(oldConn -> {
                log.infof(
                        "Closing stale WebSocket %s for platformId %s (replaced by %s)", oldConn.id(), pid, conn.id());
                connections.remove(oldConn.id());
                sessionStore.unregister(pid);
                oldConn.close().subscribe().with(v -> {}, err -> {});
            });

            connections.put(conn.id(), new ConnectionState(platformId, amshaSessionId, !requireAuth));

            log.debugf(
                    "WebSocket mapping established: connId=%s -> platformId=%s, amshaSession=%s",
                    conn.id(), platformId, amshaSessionId);

            if (!requireAuth) {
                setupAuthenticatedSession(conn, platformId, amshaSessionId);
            } else {
                log.infof("WebSocket pending auth: connId=%s platformId=%s", conn.id(), platformId);
            }
        } catch (Exception e) {
            log.errorf(
                    "Failed to initialize WebSocket connection %s: %s\n%s",
                    conn.id(), LogUtil.rootMessage(e), LogUtil.trimmedStack(e));
            // Cleanup and close to prevent orphaned states
            cleanup(conn, "initialization error");
            conn.close().subscribe().with(id -> {}, err -> {});
        }
    }

    private void setupAuthenticatedSession(WebSocketConnection conn, String platformId, String amshaSessionId) {
        connections.put(conn.id(), new ConnectionState(platformId, amshaSessionId, true));

        // Track connection and mapping in the globally accessible store (§85-C)
        sessionStore.register(platformId, amshaSessionId, conn);

        // §85-B: Proactively initialize session presence in the heartbeat tracker.
        channelServices.recordHeartbeat(amshaSessionId);

        conn.sendText(buildConnectedJson(platformId, amshaSessionId))
                .subscribe()
                .with(
                        id -> {},
                        err -> log.warnf("Failed to send connected event to %s: %s", conn.id(), err.getMessage()));

        log.infof("WebSocket ready: connId=%s platformId=%s amshaSession=%s", conn.id(), platformId, amshaSessionId);
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection conn) {
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject json = reader.readObject();
            String type = json.getString("type", "");

            ConnectionState cs = connections.get(conn.id());
            if (requireAuth && (cs == null || !cs.authenticated())) {
                if ("AUTH".equals(type)) {
                    String token = json.getString("token", "");
                    if (wsTokenStore.validateAndConsumeToken(token)) {
                        String platformId = cs != null ? cs.platformId() : null;
                        String amshaSessionId = cs != null ? cs.amshaSessionId() : null;
                        setupAuthenticatedSession(conn, platformId, amshaSessionId);
                    } else {
                        conn.sendTextAndAwait("{\"type\":\"error\",\"text\":\"Invalid or expired token\"}");
                        var _ = conn.close();
                    }
                } else {
                    conn.sendTextAndAwait("{\"type\":\"error\",\"text\":\"Authentication required\"}");
                    var _ = conn.close();
                }
                return;
            }

            if (!"message".equals(type)) {
                return;
            }
            String text = json.getString("text", "");
            if (text.isBlank()) {
                return;
            }

            ConnectionState messageCs = connections.get(conn.id());
            if (messageCs == null || messageCs.platformId() == null) {
                log.warnf(
                        "Received message without a platformId mapped for connection %s. connections.size=%d, requireAuth=%b",
                        conn.id(), connections.size(), requireAuth);
                return;
            }
            String platformId = messageCs.platformId();

            ChannelUser user = new ChannelUser(platformId, platformId, "Web User", ChannelType.WEBSOCKET);

            ChannelMessage.WebSocketMessage msg =
                    new ChannelMessage.WebSocketMessage(user, text, platformId, Instant.now());

            // ChannelServices.processAsync propagates CDI context and handles pipeline orchestration
            var _ = channelServices.processAsync(msg).exceptionally(ex -> {
                log.warnf("WebSocket pipeline error: %s", LogUtil.rootMessage(ex));
                return null;
            });

        } catch (Exception e) {
            log.warnf("Failed to process incoming WebSocket message: %s\n%s", message, LogUtil.trimmedStack(e));
        }
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        cleanup(conn, "closed");
    }

    @OnError
    public void onError(WebSocketConnection conn, Throwable t) {
        cleanup(conn, "error: " + t.getMessage());
    }

    private void cleanup(WebSocketConnection conn, String reason) {
        String connId = conn.id();
        ConnectionState removed = connections.remove(connId);
        String platformId = removed != null ? removed.platformId() : null;
        String amshaSessionId = removed != null ? removed.amshaSessionId() : null;

        if (platformId != null) {
            sessionStore.unregister(platformId);
        }

        // Terminate using Amsha Agent sessionId - not platformId (§78-fix-2)
        if (amshaSessionId != null && terminatingSessions.add(amshaSessionId)) {
            try {
                // HITL sessions must survive WebSocket disconnects - user may reconnect
                // from another tab or device to approve/deny.
                if (pendingApprovalChecker.hasPendingApproval(amshaSessionId)) {
                    log.infof(
                            "WebSocket %s: connId=%s amshaSession=%s - skipping termination (AWAITING_APPROVAL pending)",
                            reason, connId, amshaSessionId);
                    return; // SessionReaper handles cleanup after HITL timeout
                }

                // Notify UI that this is an intentional server-side termination
                conn.sendText("{\"type\":\"session_terminated\"}").subscribe().with(v -> {}, err -> {});

                channelServices.terminateSession(amshaSessionId);
                log.infof("WebSocket %s: connId=%s amshaSession=%s terminated", reason, connId, amshaSessionId);
            } finally {
                terminatingSessions.remove(amshaSessionId);
            }
        } else if (amshaSessionId != null) {
            log.debugf(
                    "WebSocket %s: connId=%s amshaSession=%s - termination already in progress",
                    reason, connId, amshaSessionId);
        } else {
            log.debugf(
                    "WebSocket %s: connId=%s (platformId=%s) - no Amsha Agent session found for termination",
                    reason, connId, platformId);
        }
    }

    /**
     * Returns both platformId and amshaSessionId in the connected event.
     * The UI MUST use amshaSessionId for all REST calls (/api/v1/hitl, /api/v1/sessions/{id}/approve).
     * The UI MUST use platformId only as the local WebSocket reference if needed.
     */
    private String buildConnectedJson(String platformId, String amshaSessionId) {
        return Json.createObjectBuilder()
                .add("type", "connected")
                .add("sessionId", amshaSessionId) // use this for REST/HITL calls
                .add("platformId", platformId) // WebSocket connection reference
                .build()
                .toString();
    }
}
