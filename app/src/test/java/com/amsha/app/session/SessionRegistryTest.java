package com.amsha.app.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.stream.IntStream;

import com.amsha.persistence.api.SessionCatalogStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    private SessionRegistry registry;
    private SessionCatalogStore sessionCatalogStore;

    @BeforeEach
    void setup() {
        sessionCatalogStore = mock(SessionCatalogStore.class);
        registry = new SessionRegistry();
        registry.sessionCatalogStore = sessionCatalogStore;
    }

    @Test
    void testFloodSessions_NoOom() {
        when(sessionCatalogStore.resolveActive(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        IntStream.range(0, 1000).parallel().forEach(i -> {
            registry.resolveOrCreate("test", "chan-" + i, "user-" + i);
        });

        // Verify we registered sessions
        verify(sessionCatalogStore, times(1000)).activateNew(any());
    }
}
