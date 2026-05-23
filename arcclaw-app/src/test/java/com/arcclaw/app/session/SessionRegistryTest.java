package com.arcclaw.app.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.stream.IntStream;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.result.ResultIterable;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    private SessionRegistry registry;
    private Jdbi jdbi;
    private Handle handle;
    private Query query;
    private Update update;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        jdbi = mock(Jdbi.class);
        handle = mock(Handle.class);
        query = mock(Query.class);
        update = mock(Update.class);

        registry = new SessionRegistry(jdbi);

        // Stub withHandle
        when(jdbi.withHandle(any())).thenAnswer(invocation -> {
            HandleCallback<?, Exception> callback = invocation.getArgument(0);
            return callback.withHandle(handle);
        });

        // Stub useHandle
        doAnswer(invocation -> {
            HandleConsumer<Exception> consumer = invocation.getArgument(0);
            consumer.useHandle(handle);
            return null;
        }).when(jdbi).useHandle(any());

        // Stub query and update builders
        when(handle.createQuery(anyString())).thenReturn(query);
        when(query.bind(anyString(), any(Object.class))).thenReturn(query);
        when(query.bind(anyString(), anyString())).thenReturn(query);

        ResultIterable<String> resultIterable = mock(
                ResultIterable.class);
        when(query.mapTo(String.class)).thenReturn(resultIterable);
        when(resultIterable.findFirst()).thenReturn(Optional.empty());

        when(handle.createUpdate(anyString())).thenReturn(update);
        when(update.bind(anyString(), any(Object.class))).thenReturn(update);
        when(update.bind(anyString(), anyString())).thenReturn(update);
        when(update.execute()).thenReturn(1);
    }

    @Test
    void testFloodSessions_NoOom() {
        IntStream.range(0, 1000).parallel().forEach(i -> {
            registry.resolveOrCreate("test", "chan-" + i, "user-" + i);
        });

        // Verify we performed the inserts via Update execution
        verify(update, times(1000)).execute();
    }
}
