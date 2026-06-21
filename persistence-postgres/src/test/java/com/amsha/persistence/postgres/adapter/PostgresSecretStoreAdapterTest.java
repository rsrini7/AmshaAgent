package com.amsha.persistence.postgres.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.persistence.api.SecretStorePort;
import com.amsha.persistence.postgres.PostgresSecretStoreAdapter;

import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying PostgresSecretStoreAdapter implements SecretStorePort.
 * Full integration tests run against embedded Postgres via Zonky.
 */
class PostgresSecretStoreAdapterTest {

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
}
