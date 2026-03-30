package com.arcclaw.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.core.security.AutonomyLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionHeartbeatTrackerTest {

    private SessionHeartbeatTracker heartbeatTracker;

    @BeforeEach
    void setUp() {
        heartbeatTracker = new SessionHeartbeatTracker();
    }

    @Test
    void testBindOwnerAndGetOwner() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);

        heartbeatTracker.bindOwner("sess1", "key-123");

        Optional<String> owner = heartbeatTracker.getOwner("sess1");
        assertTrue(owner.isPresent());
        assertEquals("key-123", owner.get());

        // Test non-existent session
        assertTrue(heartbeatTracker.getOwner("sess2").isEmpty());
    }

    @Test
    void testRecordHeartbeatPreservesOwner() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);
        heartbeatTracker.bindOwner("sess1", "key-123");

        // Record heartbeat again, this should preserve owner
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);

        Optional<String> owner = heartbeatTracker.getOwner("sess1");
        assertTrue(owner.isPresent());
        assertEquals("key-123", owner.get());
    }

    @Test
    void testInjectHeartbeatPreservesOwner() {
        heartbeatTracker.recordHeartbeat("sess1", AutonomyLevel.SUPERVISED);
        heartbeatTracker.bindOwner("sess1", "key-123");

        heartbeatTracker.injectHeartbeat("sess1", Instant.now());

        Optional<String> owner = heartbeatTracker.getOwner("sess1");
        assertTrue(owner.isPresent());
        assertEquals("key-123", owner.get());
    }

    @Test
    void testBindOwnerSilentNoOpIfSessionDoesNotExist() {
        heartbeatTracker.bindOwner("sess1", "key-123");
        assertFalse(heartbeatTracker.exists("sess1"));
        assertTrue(heartbeatTracker.getOwner("sess1").isEmpty());
    }
}
