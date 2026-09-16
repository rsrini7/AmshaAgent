package com.amsha.persistence.postgres.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.amsha.persistence.api.SecretDto;
import com.amsha.persistence.api.SecretStorePort;
import com.amsha.persistence.postgres.PostgresSecretStoreAdapter;
import com.amsha.persistence.postgres.jdbi.SecretJdbiDao;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link PostgresSecretStoreAdapter}.
 *
 * <p>The original class was a two-assertion smoke test (interface assignability + a reflective no-arg
 * constructor call), which left all four method bodies uncovered. The adapter is a pure delegate onto
 * {@link SecretJdbiDao}, but two things are worth pinning down rather than assuming:
 *
 * <ul>
 *   <li>the five-column round trip — a column silently transposed between the DTO and the row type would
 *       still compile and still "work", so each component is asserted individually;
 *   <li>the two delete scopes are genuinely different calls. {@code delete} is per key and
 *       {@code deleteBySessionId} is per session, and confusing them would destroy a session's other
 *       secrets.
 * </ul>
 *
 * <p>The {@code byte[]} components are matched with {@code aryEq}: Mockito compares arrays by identity, so
 * a plain {@code eq} would never match a freshly built array.
 */
class PostgresSecretStoreAdapterTest {

    private Jdbi jdbi;
    private SecretJdbiDao dao;
    private PostgresSecretStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(SecretJdbiDao.class);
        adapter = new PostgresSecretStoreAdapter(jdbi);
    }

    private void givenConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(SecretJdbiDao.class), any());
    }

    private void givenExtension() {
        when(jdbi.withExtension(eq(SecretJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    private static byte[] ciphertext() {
        return new byte[] {1, 2, 3};
    }

    private static byte[] nonce() {
        return new byte[] {9};
    }

    @Test
    void implementsSecretStorePort() {
        assertTrue(SecretStorePort.class.isAssignableFrom(PostgresSecretStoreAdapter.class));
    }

    @Test
    void hasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            var ctor = PostgresSecretStoreAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }

    @Test
    @DisplayName("save forwards every column of the DTO, in order")
    void saveForwardsEveryColumn() {
        givenConsumer();

        adapter.save(new SecretDto("sess-1", "api-key", 3, ciphertext(), nonce()));

        verify(dao).save(eq("sess-1"), eq("api-key"), eq(3), aryEq(ciphertext()), aryEq(nonce()));
    }

    @Test
    @DisplayName("find rebuilds the DTO from the DAO's row")
    void findRebuildsTheDtoFromTheRow() {
        givenExtension();
        when(dao.find("sess-1", "api-key"))
                .thenReturn(Optional.of(new SecretJdbiDao.SecretDtoRow("sess-1", "api-key", 3, ciphertext(), nonce())));

        SecretDto found = adapter.find("sess-1", "api-key").orElseThrow();

        assertEquals("sess-1", found.sessionId());
        assertEquals("api-key", found.keyName());
        assertEquals(3, found.encryptionKeyVersion());
        assertArrayEquals(ciphertext(), found.ciphertext());
        assertArrayEquals(nonce(), found.nonce());
    }

    @Test
    @DisplayName("find is empty when the DAO has no row")
    void findIsEmptyWhenTheRowIsAbsent() {
        givenExtension();
        when(dao.find("sess-1", "missing")).thenReturn(Optional.empty());

        assertTrue(adapter.find("sess-1", "missing").isEmpty());
    }

    @Test
    @DisplayName("delete is key-scoped and deleteBySessionId is session-scoped")
    void theTwoDeletesForwardTheirOwnScope() {
        givenConsumer();

        adapter.delete("sess-1", "api-key");
        adapter.deleteBySessionId("sess-1");

        verify(dao).delete("sess-1", "api-key");
        verify(dao).deleteBySessionId("sess-1");
    }
}
