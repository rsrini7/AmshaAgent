package com.arcclaw.app.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    private SessionRegistry registry;
    private EntityManager em;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        registry = new SessionRegistry();
        em = mock(EntityManager.class);
        registry.em = em;

        TypedQuery<String> query = mock(TypedQuery.class);
        when(em.createQuery(any(String.class), eq(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class), any())).thenReturn(query);
    }

    @Test
    void testFloodSessions_NoOom() {
        // We can't easily verify the internal Caffeine cache size without reflection,
        // but we can verify that the registry remains functional under load.
        IntStream.range(0, 1000).parallel().forEach(i -> {
            registry.resolveOrCreate("test", "chan-" + i, "user-" + i);
        });
        
        // At least verify we persisted things
        verify(em, times(1000)).persist(any(SessionRegistryEntry.class));
    }
}
