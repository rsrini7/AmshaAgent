package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteWebSessionStoreAdapterTest {

    @Inject
    SqliteWebSessionStoreAdapter store;

    @Test
    void testWebSessionStoreOperations() {
        String sessionId = "web-sess-" + UUID.randomUUID();
        String userId = "user-web-" + UUID.randomUUID();
        String digest = "digest-" + UUID.randomUUID();

        WebSessionDto dto = new WebSessionDto(
                sessionId,
                userId,
                digest,
                "csrf-" + UUID.randomUUID(),
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(86400),
                null,
                null,
                "{}",
                1,
                Instant.now(),
                Instant.now());

        assertTrue(store.save(dto));

        Optional<WebSessionDto> byId = store.findById(sessionId);
        assertTrue(byId.isPresent());
        assertEquals(userId, byId.get().userId());

        Optional<WebSessionDto> byDigest = store.findByTokenDigest(digest);
        assertTrue(byDigest.isPresent());
        assertEquals(sessionId, byDigest.get().sessionId());

        List<WebSessionDto> userSessions = store.listByUserId(userId);
        assertEquals(1, userSessions.size());

        assertEquals(1, store.deleteById(sessionId));
        assertTrue(store.findById(sessionId).isEmpty());

        // Blank params safety checks
        assertTrue(store.findById("").isEmpty());
        assertTrue(store.findByTokenDigest(null).isEmpty());
        assertTrue(store.listByUserId(" ").isEmpty());
        assertEquals(0, store.deleteById(null));
        assertEquals(0, store.deleteByUserId(""));
        assertEquals(0, store.deleteExpired(null));
    }

    @Test
    void deleteByUserIdRemovesEverySessionOfThatUser() {
        String userId = "user-bulk-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            String suffix = i + "-" + UUID.randomUUID();
            assertTrue(store.save(new WebSessionDto(
                    "web-bulk-" + suffix,
                    userId,
                    "digest-bulk-" + suffix,
                    "csrf-" + suffix,
                    Instant.now().plusSeconds(3600),
                    Instant.now().plusSeconds(86400),
                    null,
                    null,
                    "{}",
                    1,
                    Instant.now(),
                    Instant.now())));
        }
        assertEquals(2, store.listByUserId(userId).size());

        assertEquals(2, store.deleteByUserId(userId));

        assertTrue(store.listByUserId(userId).isEmpty());
    }
}
