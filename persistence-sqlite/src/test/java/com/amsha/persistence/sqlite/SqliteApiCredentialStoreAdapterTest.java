package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteApiCredentialStoreAdapterTest {

    @Inject
    SqliteApiCredentialStoreAdapter store;

    @Test
    void testApiCredentialStoreOperations() {
        String credId = "cred-" + UUID.randomUUID();
        String userId = "user-cred-" + UUID.randomUUID();
        String keyDigest = "digest-key-" + UUID.randomUUID();

        ApiCredentialDto dto =
                new ApiCredentialDto(credId, userId, keyDigest, "ADMIN", 1, null, null, Instant.now(), Instant.now());

        assertTrue(store.save(dto));

        Optional<ApiCredentialDto> byId = store.findById(credId);
        assertTrue(byId.isPresent());
        assertEquals(credId, byId.get().credentialId());

        Optional<ApiCredentialDto> byDigest = store.findByKeyDigest(keyDigest);
        assertTrue(byDigest.isPresent());
        assertEquals(credId, byDigest.get().credentialId());

        List<ApiCredentialDto> userCreds = store.listByUserId(userId);
        assertEquals(1, userCreds.size());

        assertEquals(1, store.deleteById(credId));
        assertTrue(store.findById(credId).isEmpty());

        // Blank params safety checks
        assertTrue(store.findById(null).isEmpty());
        assertTrue(store.findByKeyDigest("").isEmpty());
        assertTrue(store.listByUserId(" ").isEmpty());
        assertEquals(0, store.deleteById(null));
    }
}
