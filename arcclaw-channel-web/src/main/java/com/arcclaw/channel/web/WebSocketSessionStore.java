package com.arcclaw.channel.web;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.websockets.next.WebSocketConnection;

@ApplicationScoped
public class WebSocketSessionStore {
    private final ConcurrentHashMap<String, WebSocketConnection> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, WebSocketConnection conn) {
        sessions.put(sessionId, conn);
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public Optional<WebSocketConnection> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Collection<WebSocketConnection> all() {
        return sessions.values();
    }

    public void closeAll() {
        sessions.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        });
        sessions.clear();
    }
}
