package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.sqlite.testutil.FlywayEnabledTestProfile;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * {@code bootstrap_state} is a single fixed row ({@code id = 'bootstrap'}) that every test in this class
 * shares, so each test starts from a known state by clearing it. Without that reset the lifecycle test
 * below would depend on JUnit's method order.
 */
@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
class SqliteBootstrapStateStoreAdapterTest {

    @Inject
    SqliteBootstrapStateStoreAdapter store;

    @Inject
    Jdbi jdbi;

    @BeforeEach
    void resetBootstrapRow() {
        jdbi.useHandle(handle -> handle.execute("DELETE FROM bootstrap_state"));
    }

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

    @Test
    @DisplayName("the full credential lifecycle advances the revision and rejects stale revisions")
    void testCredentialLifecycleOperations() {
        Instant now = Instant.now();
        String owner = "owner-" + UUID.randomUUID();
        store.save(new BootstrapStateDto("bootstrap", "UNCLAIMED", null, null, null, null, null, 1, now));

        // UNCLAIMED -> CLAIMING
        assertTrue(store.reserveFirstOwner(owner, "ws-1", now).isPresent());
        assertEquals("CLAIMING", store.getState().orElseThrow().status());
        int revision = store.getState().orElseThrow().revision();

        // CLAIMING -> UNCLAIMED, clearing the owner
        assertTrue(store.rollbackClaiming(revision, now));
        BootstrapStateDto rolledBack = store.getState().orElseThrow();
        assertEquals("UNCLAIMED", rolledBack.status());
        assertNull(rolledBack.ownerId(), "rollback must release the reserved owner");

        // UNCLAIMED -> CLAIMING -> CREDENTIAL_ISSUED
        assertTrue(store.reserveFirstOwner(owner, "ws-1", now).isPresent());
        revision = store.getState().orElseThrow().revision();
        assertTrue(store.recordCredentialIssued(revision, "digest-a", now));
        assertEquals("CREDENTIAL_ISSUED", store.getState().orElseThrow().status());

        // A stale revision must be refused, not silently applied
        assertFalse(store.recordCredentialIssued(revision, "digest-stale", now));

        // Credential rotation while CREDENTIAL_ISSUED
        revision = store.getState().orElseThrow().revision();
        assertTrue(store.replaceCredential(revision, "digest-b", now, now));

        // CREDENTIAL_ISSUED -> CLAIMED, and only by the owner that reserved it
        revision = store.getState().orElseThrow().revision();
        assertFalse(store.confirmClaim(revision, "someone-else", now), "a non-owner must not be able to claim");
        assertTrue(store.confirmClaim(revision, owner, now));
        assertEquals("CLAIMED", store.getState().orElseThrow().status());

        // Once CLAIMED the row is no longer claimable
        revision = store.getState().orElseThrow().revision();
        assertFalse(store.reserveFirstOwner("late-owner", "ws-2", now).isPresent());
        assertEquals(
                revision, store.getState().orElseThrow().revision(), "a refused reserve must not bump the revision");
    }

    @Test
    @DisplayName("reserving a workspace already owned is refused without touching the row")
    void testReserveFirstOwnerIsRefusedWhenAlreadyClaimed() {
        Instant now = Instant.now();
        store.save(new BootstrapStateDto("bootstrap", "UNCLAIMED", null, null, null, null, null, 1, now));

        assertTrue(store.reserveFirstOwner("first-owner", "ws-1", now).isPresent());
        int revision = store.getState().orElseThrow().revision();

        assertFalse(store.reserveFirstOwner("second-owner", "ws-2", now).isPresent());
        assertEquals(revision, store.getState().orElseThrow().revision());
        assertEquals("first-owner", store.getState().orElseThrow().ownerId());
    }

    @Test
    @DisplayName("save is a no-op when the singleton row already exists")
    void testSaveIsIdempotent() {
        Instant now = Instant.now();
        store.save(new BootstrapStateDto("bootstrap", "UNCLAIMED", null, null, null, null, null, 1, now));
        int revision = store.getState().orElseThrow().revision();

        assertDoesNotThrow(() ->
                store.save(new BootstrapStateDto("bootstrap", "CLAIMED", "other", "ws", null, null, null, 99, now)));
        assertEquals(revision, store.getState().orElseThrow().revision());
        assertEquals("UNCLAIMED", store.getState().orElseThrow().status());
    }
}
