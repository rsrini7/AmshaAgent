package com.amsha.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.websockets.next.WebSocketConnection;

@ExtendWith(MockitoExtension.class)
class WebSocketSessionStoreTest {

    @Mock
    WebSocketConnection connection;

    private WebSocketSessionStore store;

    @BeforeEach
    void setUp() {
        store = new WebSocketSessionStore();
        lenient().when(connection.id()).thenReturn("conn-1");
    }

    @Test
    void testRegisterGetAndUnregister() {
        store.register("plat-1", "amsha-sess-1", connection);

        Optional<WebSocketConnection> found = store.get("plat-1");
        assertTrue(found.isPresent());
        assertEquals(connection, found.get());

        assertEquals("amsha-sess-1", store.getAmshaSessionId("plat-1").orElse(null));

        List<WebSocketConnection> connections = store.getConnectionsByAmshaSession("amsha-sess-1");
        assertEquals(1, connections.size());

        store.unregister("plat-1");
        assertTrue(store.get("plat-1").isEmpty());
    }

    @Test
    void testUpdateAmshaSession() {
        store.register("plat-1", "amsha-sess-1", connection);
        store.updateAmshaSession("plat-1", "amsha-sess-2");

        assertEquals("amsha-sess-2", store.getAmshaSessionId("plat-1").orElse(null));
        List<WebSocketConnection> connections = store.getConnectionsByAmshaSession("amsha-sess-2");
        assertEquals(1, connections.size());
    }

    @Test
    void testCloseAll() {
        store.register("plat-1", "amsha-sess-1", connection);
        assertEquals(1, store.all().size());

        store.closeAll();
        assertTrue(store.all().isEmpty());
        verify(connection).close();
    }
}
