package com.amsha.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionHeartbeatTrackerTest {

    private SessionHeartbeatTracker heartbeatTracker;

    @BeforeEach
    void setUp() {
        heartbeatTracker = new SessionHeartbeatTracker();
    }

    @Test
    void testTryBindOwnerAndGetOwner() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);

        boolean success = heartbeatTracker.tryBindOwner("sess1", "key-123");
        assertTrue(success);

        Optional<String> owner = heartbeatTracker.getOwner("sess1");
        assertTrue(owner.isPresent());
        assertEquals("key-123", owner.get());

        // Test non-existent session
        assertFalse(heartbeatTracker.tryBindOwner("sess2", "key-123"));
        assertTrue(heartbeatTracker.getOwner("sess2").isEmpty());
    }

    @Test
    void testTryBindOwnerPreventHijack() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);

        // First bind succeeds
        assertTrue(heartbeatTracker.tryBindOwner("sess1", "userA"));

        // Second bind with DIFFERENT user fails (prevents hijack)
        assertFalse(heartbeatTracker.tryBindOwner("sess1", "userB"));

        // Second bind with SAME user succeeds (idempotent)
        assertTrue(heartbeatTracker.tryBindOwner("sess1", "userA"));

        assertEquals("userA", heartbeatTracker.getOwner("sess1").get());
    }

    @Test
    void testRecordHeartbeatPreservesOwner() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);
        heartbeatTracker.tryBindOwner("sess1", "key-123");

        // Record heartbeat again, this should preserve owner
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);

        Optional<String> owner = heartbeatTracker.getOwner("sess1");
        assertTrue(owner.isPresent());
        assertEquals("key-123", owner.get());
    }

    @Test
    void testInjectHeartbeatPreservesOwner() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);
        heartbeatTracker.tryBindOwner("sess1", "key-123");

        heartbeatTracker.injectHeartbeat("sess1", Instant.now());

        Optional<String> owner = heartbeatTracker.getOwner("sess1");
        assertTrue(owner.isPresent());
        assertEquals("key-123", owner.get());
    }

    @Test
    void testBindOwnerNoOpIfSessionDoesNotExist() {
        assertFalse(heartbeatTracker.tryBindOwner("sess1", "key-123"));
        assertFalse(heartbeatTracker.exists("sess1"));
        assertTrue(heartbeatTracker.getOwner("sess1").isEmpty());
    }
}
