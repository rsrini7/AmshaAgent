package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

import io.quarkus.websockets.next.WebSocketConnection;

/**
 * Tracks active WebSocket connections and maps them to both temporary
 * platformIds (per-tab) and stable amshaSessionIds (cross-tab).
 */
@ApplicationScoped
public class WebSocketSessionStore {

    // platformId → WebSocketConnection
    private static final Logger log = Logger.getLogger(WebSocketSessionStore.class);

    private final Map<String, WebSocketConnection> platformToSession = new ConcurrentHashMap<>();

    // connId → amshaSessionId
    private final Map<String, String> connToAmshaSession = new ConcurrentHashMap<>();

    // connId → durable owner key used by authenticated channel messages
    private final Map<String, String> connToOwner = new ConcurrentHashMap<>();

    // connId → WebSocketConnection (direct lookup to avoid O(n) scans)
    private final Map<String, WebSocketConnection> connIdToConnection = new ConcurrentHashMap<>();

    // platformId → amshaSessionId (reverse lookup for broadcasts from single platform)
    private final Map<String, String> platformToAmsha = new ConcurrentHashMap<>();

    public void register(String platformId, String amshaSessionId, WebSocketConnection conn) {
        register(platformId, amshaSessionId, platformId, conn);
    }

    public void register(String platformId, String amshaSessionId, String ownerKeyName, WebSocketConnection conn) {
        platformToSession.put(platformId, conn);
        platformToAmsha.put(platformId, amshaSessionId);
        connToAmshaSession.put(conn.id(), amshaSessionId);
        connToOwner.put(conn.id(), ownerKeyName);
        connIdToConnection.put(conn.id(), conn);
    }

    public void unregister(String platformId) {
        WebSocketConnection conn = platformToSession.get(platformId);
        if (conn != null) {
            unregister(platformId, conn);
        } else {
            platformToAmsha.remove(platformId);
        }
    }

    /**
     * Removes a mapping only when it still belongs to the expected connection.
     * A close callback from an old socket must not remove a replacement socket
     * registered for the same platform.
     */
    public void unregister(String platformId, WebSocketConnection expectedConnection) {
        if (platformId == null || expectedConnection == null) {
            return;
        }
        if (!platformToSession.remove(platformId, expectedConnection)) {
            return;
        }

        String connectionId = expectedConnection.id();
        connToAmshaSession.remove(connectionId);
        connToOwner.remove(connectionId);
        connIdToConnection.remove(connectionId, expectedConnection);
        platformToAmsha.remove(platformId);
    }

    public Optional<WebSocketConnection> get(String platformId) {
        return Optional.ofNullable(platformToSession.get(platformId));
    }

    public Optional<String> getAmshaSessionId(String platformId) {
        return Optional.ofNullable(platformToAmsha.get(platformId));
    }

    public List<WebSocketConnection> getConnectionsByAmshaSession(String amshaSessionId) {
        if (amshaSessionId == null) return Collections.emptyList();

        return connToAmshaSession.entrySet().stream()
                .filter(e -> amshaSessionId.equals(e.getValue()))
                .map(e -> findConnectionById(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    public List<WebSocketConnection> getConnectionsByOwner(String ownerKeyName) {
        if (ownerKeyName == null) return Collections.emptyList();

        return connToOwner.entrySet().stream()
                .filter(entry -> ownerKeyName.equals(entry.getValue()))
                .map(entry -> findConnectionById(entry.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    private WebSocketConnection findConnectionById(String connId) {
        return connIdToConnection.get(connId);
    }

    public Collection<WebSocketConnection> all() {
        return platformToSession.values();
    }

    public void closeAll() {
        platformToSession.values().forEach(conn -> {
            try {
                var _ = conn.close();
            } catch (Exception e) {
                log.debugf("Failed to close WebSocket connection: %s", LogUtil.rootMessage(e));
            }
        });
        platformToSession.clear();
        connToAmshaSession.clear();
        connToOwner.clear();
        platformToAmsha.clear();
        connIdToConnection.clear();
    }

    public void updateAmshaSession(String platformId, String newAmshaSessionId) {
        WebSocketConnection conn = platformToSession.get(platformId);
        if (conn != null) {
            updateAmshaSession(platformId, conn, newAmshaSessionId);
        }
    }

    /**
     * Updates a session mapping only when it still belongs to the expected
     * connection, closing the same race as {@link #unregister(String,
     * WebSocketConnection)}.
     */
    public void updateAmshaSession(
            String platformId, WebSocketConnection expectedConnection, String newAmshaSessionId) {
        if (platformId == null || expectedConnection == null || newAmshaSessionId == null) {
            return;
        }
        if (!expectedConnection.equals(platformToSession.get(platformId))) {
            return;
        }
        connToAmshaSession.put(expectedConnection.id(), newAmshaSessionId);
        connIdToConnection.put(expectedConnection.id(), expectedConnection);
        platformToAmsha.put(platformId, newAmshaSessionId);
    }
}
