package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteBootstrapStateStoreAdapterTest {

    @Inject
    SqliteBootstrapStateStoreAdapter store;

    @Test
    void testBootstrapStateStoreOperations() {
        BootstrapStateDto initialState =
                new BootstrapStateDto("bootstrap", "UNCLAIMED", null, null, null, null, null, 1, Instant.now());
        store.save(initialState);

        Optional<BootstrapStateDto> stateOpt = store.getState();
        assertTrue(stateOpt.isPresent());
        assertNotNull(stateOpt.get().id());
        assertNotNull(stateOpt.get().status());
    }
}
