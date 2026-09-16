package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.persistence.api.PersistenceAdapterPort;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresPersistenceAdapter}.
 *
 * <p>No test class mentioned this adapter at all, so all 7 of its lines were uncovered. It exists to wrap a
 * caller-supplied {@link com.amsha.persistence.api.PersistenceAction} in a JDBI transaction, which makes the
 * only interesting questions about it: does the action actually run, does it run <em>inside</em> the
 * transaction, and does a failure escape rather than being swallowed.
 *
 * <p>One behaviour is recorded rather than endorsed: {@code sessionId} and {@code operationName} are
 * accepted and then ignored — they never reach the transaction and are not validated. {@code identifiersAreNotValidated}
 * pins that down so that adding validation later is a deliberate change with a failing test, not a silent one.
 */
class PostgresPersistenceAdapterTest {

    private Jdbi jdbi;
    private PostgresPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        adapter = new PostgresPersistenceAdapter(jdbi);
    }

    /** Runs the consumer the adapter hands to {@code useTransaction} against a mocked handle. */
    private void givenTransaction() {
        Handle handle = mock(Handle.class);
        doAnswer(invocation -> {
                    HandleConsumer<RuntimeException> consumer = invocation.getArgument(0);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useTransaction(any());
    }

    @Test
    void implementsPersistenceAdapterPort() {
        assertTrue(PersistenceAdapterPort.class.isAssignableFrom(PostgresPersistenceAdapter.class));
    }

    @Test
    @DisplayName("the protected no-arg constructor builds an unconfigured instance")
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        assertDoesNotThrow(() -> {
            var ctor = PostgresPersistenceAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }

    @Test
    @DisplayName("execute runs the action exactly once, inside the transaction")
    void executeRunsTheActionInsideATransaction() {
        givenTransaction();
        AtomicInteger runs = new AtomicInteger();

        adapter.execute("sess-1", "flush", runs::incrementAndGet);

        assertEquals(1, runs.get());
        verify(jdbi).useTransaction(any());
    }

    @Test
    @DisplayName("a failing action propagates out of execute")
    void aFailingActionPropagates() {
        givenTransaction();

        // Letting the failure escape is what lets JDBI roll the transaction back.
        assertThrows(
                IllegalStateException.class,
                () -> adapter.execute("sess-1", "flush", () -> {
                    throw new IllegalStateException("boom");
                }));
    }

    @Test
    @DisplayName("sessionId and operationName are accepted but never validated")
    void identifiersAreNotValidated() {
        givenTransaction();
        AtomicInteger runs = new AtomicInteger();

        adapter.execute(null, null, runs::incrementAndGet);

        assertEquals(1, runs.get(), "the identifiers are metadata only; the action still runs");
    }
}
