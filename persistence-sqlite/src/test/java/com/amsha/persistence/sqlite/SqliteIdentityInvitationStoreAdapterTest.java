package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteIdentityInvitationStoreAdapterTest {

    @Inject
    SqliteIdentityInvitationStoreAdapter store;

    @Test
    void testIdentityInvitationStoreOperations() {
        String invId = "inv-" + UUID.randomUUID();
        String wsId = "ws-" + UUID.randomUUID();
        String digest = "inv-digest-" + UUID.randomUUID();

        IdentityInvitationDto dto = new IdentityInvitationDto(
                invId,
                wsId,
                "MEMBER",
                digest,
                "user@example.com",
                Instant.now().plusSeconds(86400),
                null,
                null,
                1,
                Instant.now(),
                Instant.now());

        assertTrue(store.save(dto));

        Optional<IdentityInvitationDto> byId = store.findById(invId);
        assertTrue(byId.isPresent());
        assertEquals("user@example.com", byId.get().optionalEmailConstraint());

        Optional<IdentityInvitationDto> byDigest = store.findByTokenDigest(digest);
        assertTrue(byDigest.isPresent());
        assertEquals(invId, byDigest.get().invitationId());

        List<IdentityInvitationDto> list = store.listByWorkspaceId(wsId);
        assertEquals(1, list.size());

        assertEquals(1, store.deleteById(invId));
        assertTrue(store.findById(invId).isEmpty());

        // Blank safety checks
        assertTrue(store.findById(null).isEmpty());
        assertTrue(store.findByTokenDigest("").isEmpty());
        assertTrue(store.listByWorkspaceId(" ").isEmpty());
        assertEquals(0, store.deleteById(null));
        assertEquals(0, store.deleteExpired(null));
    }
}
