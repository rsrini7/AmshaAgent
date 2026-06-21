package com.amsha.persistence.sqlite.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.persistence.api.SecretStorePort;
import com.amsha.persistence.sqlite.SqliteSecretStoreAdapter;

import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying SqliteSecretStoreAdapter implements SecretStorePort.
 * Full integration tests are in {@code AgentCronRepositoryTest} and
 * {@code JdbiCheckpointConcurrencyTest} which run against a real DB.
 */
class SqliteSecretStoreAdapterTest {

    @Test
    void implementsSecretStorePort() {
        assertTrue(SecretStorePort.class.isAssignableFrom(SqliteSecretStoreAdapter.class));
    }

    @Test
    void hasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            var ctor = SqliteSecretStoreAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }
}
