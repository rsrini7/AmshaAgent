package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.session.SessionRotatedEvent;
import com.amsha.common.util.LogUtil;
import com.amsha.security.api.MediaBudgetLimits;

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
    private static final int MAX_AUDIO_UPLOAD_BYTES = (int) MediaBudgetLimits.DEFAULT_MAX_DOCUMENT_SIZE_BYTES;
    private static final int MAX_AUDIO_BASE64_CHARS = 4 * ((MAX_AUDIO_UPLOAD_BYTES + 2) / 3);
    private static final int MAX_FILE_NAME_CHARS = 255;
    private static final Set<String> SUPPORTED_AUDIO_MIME_TYPES =
            Set.of("audio/mpeg", "audio/wav", "audio/ogg", "audio/webm");

    @Inject
    WebSocketSessionStore sessionStore;

    @Inject
    ChannelServices channelServices;

    @Inject
    WsTokenStore wsTokenStore;

    private record ConnectionState(
            String platformId, String amshaSessionId, boolean authenticated, String ownerKeyName) {}

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
                sessionStore.unregister(pid, oldConn);
                oldConn.close().subscribe().with(v -> {}, err -> {});
            });

            connections.put(conn.id(), new ConnectionState(platformId, amshaSessionId, false, null));

            log.debugf(
                    "WebSocket mapping established: connId=%s -> platformId=%s, amshaSession=%s",
                    conn.id(), platformId, amshaSessionId);

            if (!requireAuth) {
                setupAuthenticatedSession(conn, platformId, amshaSessionId, platformId);
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

    private void setupAuthenticatedSession(
            WebSocketConnection conn, String platformId, String amshaSessionId, String ownerKeyName) {
        String effectiveSessionId = amshaSessionId;
        if (!ownerKeyName.equals(platformId)) {
            effectiveSessionId = channelServices.resolveOrCreateSession(
                    ChannelType.WEBSOCKET.id().toUpperCase(Locale.ROOT), platformId, ownerKeyName);
            if (amshaSessionId != null && !amshaSessionId.equals(effectiveSessionId)) {
                channelServices.terminateSession(amshaSessionId);
            }
        }
        channelServices.bindSessionOwner(effectiveSessionId, ownerKeyName);
        connections.put(conn.id(), new ConnectionState(platformId, effectiveSessionId, true, ownerKeyName));

        // Track connection and mapping in the globally accessible store (§85-C)
        sessionStore.register(platformId, effectiveSessionId, ownerKeyName, conn);

        // §85-B: Proactively initialize session presence in the heartbeat tracker.
        channelServices.recordHeartbeat(effectiveSessionId);

        conn.sendText(buildConnectedJson(platformId, effectiveSessionId))
                .subscribe()
                .with(
                        id -> {},
                        err -> log.warnf(
                                "Failed to send connected event to %s: %s", conn.id(), LogUtil.rootMessage(err)));

        log.infof(
                "WebSocket ready: connId=%s platformId=%s amshaSession=%s owner=%s",
                conn.id(), platformId, effectiveSessionId, ownerKeyName);
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection conn) {
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject json = reader.readObject();
            String type = json.getString("type", "");

            ConnectionState cs = connections.get(conn.id());
            if ("AUTH".equals(type)) {
                String token = json.getString("token", "");
                Optional<String> owner = wsTokenStore.validateAndConsumeToken(token);
                if (owner.isPresent() && (cs == null || !owner.get().equals(cs.ownerKeyName()))) {
                    String platformId = cs != null ? cs.platformId() : null;
                    String amshaSessionId = cs != null ? cs.amshaSessionId() : null;
                    setupAuthenticatedSession(conn, platformId, amshaSessionId, owner.get());
                } else if (owner.isEmpty() && requireAuth) {
                    conn.sendTextAndAwait("{\"type\":\"error\",\"text\":\"Invalid or expired token\"}");
                    var _ = conn.close();
                }
                return;
            }
            if (requireAuth && (cs == null || !cs.authenticated())) {
                conn.sendTextAndAwait("{\"type\":\"error\",\"text\":\"Authentication required\"}");
                var _ = conn.close();
                return;
            }

            if (!"message".equals(type)) {
                return;
            }
            String text = json.getString("text", "");
            MediaAttachment attachment = parseAudioAttachment(json);
            if (text.isBlank() && attachment == null) {
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

            String userId = messageCs.ownerKeyName() != null ? messageCs.ownerKeyName() : platformId;
            ChannelUser user = new ChannelUser(userId, platformId, "Web User", ChannelType.WEBSOCKET);

            List<MediaAttachment> attachments = attachment == null ? List.of() : List.of(attachment);
            ChannelMessage.WebSocketMessage msg =
                    new ChannelMessage.WebSocketMessage(user, text, platformId, Instant.now(), attachments);

            // ChannelServices.processAsync propagates CDI context and handles pipeline orchestration
            var _ = channelServices.processAsync(msg).exceptionally(ex -> {
                log.warnf("WebSocket pipeline error: %s", LogUtil.rootMessage(ex));
                return null;
            });

        } catch (IllegalArgumentException e) {
            log.warnf("Rejected WebSocket message: %s", LogUtil.rootMessage(e));
            sendClientError(conn, LogUtil.rootMessage(e));
        } catch (Exception e) {
            log.warnf("Failed to process incoming WebSocket message: %s", LogUtil.rootMessage(e));
            log.debugf("WebSocket message processing stack: %s", LogUtil.trimmedStack(e));
            sendClientError(conn, "Message could not be processed");
        }
    }

    private static MediaAttachment parseAudioAttachment(JsonObject message) {
        if (!message.containsKey("attachment") || message.isNull("attachment")) {
            return null;
        }
        JsonObject attachment = message.getJsonObject("attachment");
        String mimeType = normalizeAudioMimeType(attachment.getString("mimeType", ""));
        if (!SUPPORTED_AUDIO_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Supported audio formats are MP3, WAV, OGG, and WebM");
        }
        String encoded = attachment.getString("data", "");
        if (encoded.isBlank()) {
            throw new IllegalArgumentException("Audio attachment is empty");
        }
        if (encoded.length() > MAX_AUDIO_BASE64_CHARS) {
            throw new IllegalArgumentException("Audio attachment exceeds 25 MiB");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("Audio attachment encoding is invalid");
        }
        if (bytes.length == 0 || bytes.length > MAX_AUDIO_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Audio attachment must be between 1 byte and 25 MiB");
        }
        return MediaAttachment.ofInline(
                UUID.randomUUID().toString(),
                AttachmentType.AUDIO,
                mimeType,
                safeFileName(attachment.getString("fileName", ""), mimeType),
                bytes,
                Map.of("channel", ChannelType.WEBSOCKET.id()));
    }

    private static String normalizeAudioMimeType(String mimeType) {
        String normalized =
                mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "audio/mp3" -> "audio/mpeg";
            case "audio/x-wav", "audio/wave" -> "audio/wav";
            case "video/webm" -> "audio/webm";
            default -> normalized;
        };
    }

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
            candidate = switch (mimeType) {
                case "audio/mpeg" -> "audio.mp3";
                case "audio/wav" -> "audio.wav";
                case "audio/ogg" -> "audio.ogg";
                case "audio/webm" -> "audio.webm";
                default -> "audio.bin";
            };
        }
        return candidate.length() <= MAX_FILE_NAME_CHARS ? candidate : candidate.substring(0, MAX_FILE_NAME_CHARS);
    }

    private static void sendClientError(WebSocketConnection conn, String detail) {
        if (conn == null) {
            return;
        }
        conn.sendTextAndAwait(Json.createObjectBuilder()
                .add("type", "error")
                .add("text", detail == null || detail.isBlank() ? "Invalid message" : detail)
                .build()
                .toString());
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        cleanup(conn, "closed");
    }

    @OnError
    public void onError(WebSocketConnection conn, Throwable t) {
        cleanup(conn, "error: " + LogUtil.rootMessage(t));
    }

    private void cleanup(WebSocketConnection conn, String reason) {
        String connId = conn.id();
        ConnectionState removed = connections.remove(connId);
        String platformId = removed != null ? removed.platformId() : null;
        String amshaSessionId = removed != null ? removed.amshaSessionId() : null;

        if (platformId != null) {
            sessionStore.unregister(platformId, conn);
        }

        // A browser refresh or tab close must not terminate the session.
        // The workspace continues running and will be reattached on reconnect.
        if (amshaSessionId != null) {
            log.infof(
                    "WebSocket %s: connId=%s amshaSession=%s disconnected - session preserved",
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

    void onSessionRotated(@Observes SessionRotatedEvent event) {
        connections.forEach((connId, state) -> {
            if (state.amshaSessionId().equals(event.oldSessionId())) {
                Optional<WebSocketConnection> currentConnection =
                        sessionStore.get(state.platformId()).filter(connection -> connId.equals(connection.id()));
                if (currentConnection.isEmpty()) {
                    return;
                }
                ConnectionState updated = new ConnectionState(
                        state.platformId(), event.newSessionId(), state.authenticated(), state.ownerKeyName());
                connections.put(connId, updated);
                sessionStore.updateAmshaSession(state.platformId(), currentConnection.get(), event.newSessionId());
                log.infof(
                        "WebSocket: rotated session for connId=%s %s → %s",
                        connId, event.oldSessionId(), event.newSessionId());

                currentConnection
                        .get()
                        .sendText(Json.createObjectBuilder()
                                .add("type", "session-rotated")
                                .add("sessionId", event.newSessionId())
                                .build()
                                .toString())
                        .subscribe()
                        .with(
                                v -> {},
                                err -> log.warnf(
                                        "Failed to send session-rotated to %s: %s", connId, LogUtil.rootMessage(err)));
            }
        });
    }
}
