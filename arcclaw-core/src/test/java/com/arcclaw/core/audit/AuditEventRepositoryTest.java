package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AuditEventRepositoryTest {

    @Inject
    AuditEventRepository repository;

    @Inject
    AuditHashUtil hashUtil;

    @Test
    @Transactional
    public void testRecordAndFindSession() {
        String sessionId = "test-session-" + System.currentTimeMillis();
        AuditEvent event = AuditEvent.of(sessionId, "user1", "coordinator", "hash1",
                AuditOutcome.FINISH, SecurityEventType.LOOP_FINISH, 100, 200, BigDecimal.valueOf(0.01), 1, null);
        
        repository.record(event);

        List<AuditEvent> found = repository.findBySessionId(sessionId);
        assertEquals(1, found.size());
        assertEquals("user1", found.get(0).userId);
    }

    @Test
    @Transactional
    public void testHmacChainGeneration() {
        // Use timestamp-based unique ID — never reuses across test runs
        String sessionId = "test-hmac-chain-" + System.currentTimeMillis();
        
        AuditEvent e1 = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "t1");
        repository.record(e1);
        
        AuditEvent e2 = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "t2");
        repository.record(e2);
        
        AuditEvent e3 = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "t3");
        repository.record(e3);

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(3, events.size());

        // e1 should link to genesis
        String expectedGenesis = hashUtil.sha256hex("GENESIS:" + sessionId);
        assertEquals(expectedGenesis, events.get(0).prevHash);
        
        // e2 should link to e1
        assertEquals(events.get(0).hmacHash, events.get(1).prevHash);
        
        // e3 should link to e2
        assertEquals(events.get(1).hmacHash, events.get(2).prevHash);
    }
}
