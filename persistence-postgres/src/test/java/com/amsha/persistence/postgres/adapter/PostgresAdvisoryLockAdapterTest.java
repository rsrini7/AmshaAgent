package com.amsha.persistence.postgres.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.postgres.PostgresAdvisoryLockAdapter;

import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying PostgresAdvisoryLockAdapter implements AdvisoryLockPort.
 * Full integration tests run against embedded Postgres via Zonky.
 */
class PostgresAdvisoryLockAdapterTest {

    @Test
    void implementsAdvisoryLockPort() {
        assertTrue(AdvisoryLockPort.class.isAssignableFrom(PostgresAdvisoryLockAdapter.class));
    }

    @Test
    void hasDefaultConstructor() {
        assertDoesNotThrow(() -> {
            var ctor = PostgresAdvisoryLockAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }
}
