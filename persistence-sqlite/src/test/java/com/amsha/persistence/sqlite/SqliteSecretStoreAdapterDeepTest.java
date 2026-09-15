package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import com.amsha.persistence.api.SecretDto;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-database coverage for {@link SqliteSecretStoreAdapter}.
 *
 * <p>The existing {@code adapter.SqliteSecretStoreAdapterTest} only asserts that the class
 * implements its port and has a no-arg constructor — it never calls a method, leaving all
 * 10 of the adapter's executable lines uncovered. These tests run the adapter's real SQL
 * against a real SQLite database built from the {@code V3__security_and_audit.sql} DDL.
 *
 * <p>Plain JUnit (no Quarkus container): the adapter only needs a {@link Jdbi}, and a
 * file-backed temp database is enough. It is file-backed rather than {@code :memory:}
 * because each JDBI operation opens its own connection and an in-memory database would be
 * empty for every call after the first.
 */
class SqliteSecretStoreAdapterDeepTest {

    private static final String DDL = """
            CREATE TABLE encrypted_secrets (
                session_id            TEXT NOT NULL,
                key_name              TEXT NOT NULL,
                encryption_key_version INTEGER NOT NULL DEFAULT 1,
                ciphertext            BLOB NOT NULL,
                nonce                 BLOB NOT NULL,
                PRIMARY KEY (session_id, key_name)
            )
            """;

    @TempDir
    Path tempDir;

    private SqliteSecretStoreAdapter store;

    @BeforeEach
    void setUp() {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + tempDir.resolve("secrets.db").toAbsolutePath())
                .installPlugin(new SqlObjectPlugin());
        jdbi.useHandle(handle -> handle.execute(DDL));
        store = new SqliteSecretStoreAdapter(jdbi);
    }

    @Test
    void saveFindDeleteRoundTrip() {
        String sessionId = "session-1";
        byte[] ciphertext = {1, 2, 3, 4};
        byte[] nonce = {9, 8};

        assertTrue(store.find(sessionId, "api-key").isEmpty(), "an unknown key must read as empty");

        store.save(new SecretDto(sessionId, "api-key", 3, ciphertext, nonce));

        Optional<SecretDto> found = store.find(sessionId, "api-key");
        assertTrue(found.isPresent());
        assertEquals(sessionId, found.get().sessionId());
        assertEquals("api-key", found.get().keyName());
        assertEquals(3, found.get().encryptionKeyVersion());
        assertArrayEquals(ciphertext, found.get().ciphertext());
        assertArrayEquals(nonce, found.get().nonce());

        store.delete(sessionId, "api-key");
        assertTrue(store.find(sessionId, "api-key").isEmpty());
    }

    @Test
    void saveReplacesTheExistingRowForTheSameSessionAndKey() {
        store.save(new SecretDto("session-1", "api-key", 1, new byte[] {1}, new byte[] {2}));
        store.save(new SecretDto("session-1", "api-key", 2, new byte[] {3}, new byte[] {4}));

        SecretDto found = store.find("session-1", "api-key").orElseThrow();
        assertEquals(2, found.encryptionKeyVersion());
        assertArrayEquals(new byte[] {3}, found.ciphertext());
    }

    @Test
    void theSameKeyNameInTwoSessionsIsStoredIndependently() {
        store.save(new SecretDto("session-a", "api-key", 1, new byte[] {10}, new byte[] {1}));
        store.save(new SecretDto("session-b", "api-key", 1, new byte[] {20}, new byte[] {2}));

        assertArrayEquals(
                new byte[] {10},
                store.find("session-a", "api-key").orElseThrow().ciphertext());
        assertArrayEquals(
                new byte[] {20},
                store.find("session-b", "api-key").orElseThrow().ciphertext());
    }

    @Test
    void deleteBySessionIdRemovesEveryKeyForThatSessionOnly() {
        store.save(new SecretDto("session-a", "key-1", 1, new byte[] {1}, new byte[] {1}));
        store.save(new SecretDto("session-a", "key-2", 1, new byte[] {2}, new byte[] {2}));
        store.save(new SecretDto("session-b", "key-1", 1, new byte[] {3}, new byte[] {3}));

        store.deleteBySessionId("session-a");

        assertTrue(store.find("session-a", "key-1").isEmpty());
        assertTrue(store.find("session-a", "key-2").isEmpty());
        assertTrue(store.find("session-b", "key-1").isPresent(), "another session's secrets must survive");
    }

    @Test
    void deleteOfAMissingRowIsANoOp() {
        store.save(new SecretDto("session-1", "kept", 1, new byte[] {1}, new byte[] {1}));

        store.delete("session-1", "absent");
        store.deleteBySessionId("absent-session");

        assertTrue(store.find("session-1", "kept").isPresent());
    }

    @Test
    void findDegradesToEmptyWhenTheTableDoesNotExistYet() {
        // The adapter's documented startup case: the query fails because Flyway has not run,
        // and the adapter must return empty rather than propagating the error.
        Jdbi bare = Jdbi.create("jdbc:sqlite:" + tempDir.resolve("bare.db").toAbsolutePath());
        SqliteSecretStoreAdapter detached = new SqliteSecretStoreAdapter(bare);

        assertTrue(detached.find("session-1", "api-key").isEmpty());
    }
}
