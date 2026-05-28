package com.arcclaw.channel.web;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.common.util.LogUtil;
import io.quarkus.websockets.next.WebSocketConnection;
import org.jboss.logging.Logger;

/**
 * Tracks active WebSocket connections and maps them to both temporary 
 * platformIds (per-tab) and stable arcclawSessionIds (cross-tab).
 */
@ApplicationScoped
public class WebSocketSessionStore {
    
    // platformId → WebSocketConnection
    private static final Logger log = Logger.getLogger(WebSocketSessionStore.class);

    private final Map<String, WebSocketConnection> platformToSession = new ConcurrentHashMap<>();
    
    // connId → arcclawSessionId
    private final Map<String, String> connToArcclawSession = new ConcurrentHashMap<>();

    // connId → WebSocketConnection (direct lookup to avoid O(n) scans)
    private final Map<String, WebSocketConnection> connIdToConnection = new ConcurrentHashMap<>();

    // platformId → arcclawSessionId (reverse lookup for broadcasts from single platform)
    private final Map<String, String> platformToArcclaw = new ConcurrentHashMap<>();

    public void register(String platformId, String arcclawSessionId, WebSocketConnection conn) {
        platformToSession.put(platformId, conn);
        platformToArcclaw.put(platformId, arcclawSessionId);
        connToArcclawSession.put(conn.id(), arcclawSessionId);
        connIdToConnection.put(conn.id(), conn);
    }

    public void unregister(String platformId) {
        WebSocketConnection conn = platformToSession.remove(platformId);
        if (conn != null) {
            connToArcclawSession.remove(conn.id());
            connIdToConnection.remove(conn.id());
        }
        platformToArcclaw.remove(platformId);
    }

    public Optional<WebSocketConnection> get(String platformId) {
        return Optional.ofNullable(platformToSession.get(platformId));
    }

    public Optional<String> getArcclawSessionId(String platformId) {
        return Optional.ofNullable(platformToArcclaw.get(platformId));
    }

    public List<WebSocketConnection> getConnectionsByArcclawSession(String arcclawSessionId) {
        if (arcclawSessionId == null) return Collections.emptyList();
        
        return connToArcclawSession.entrySet().stream()
            .filter(e -> arcclawSessionId.equals(e.getValue()))
            .map(e -> findConnectionById(e.getKey()))
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
        connToArcclawSession.clear();
        platformToArcclaw.clear();
        connIdToConnection.clear();
    }
}
