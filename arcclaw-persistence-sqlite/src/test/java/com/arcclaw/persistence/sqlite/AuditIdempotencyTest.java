package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.audit.AuditEventEntity;
import com.arcclaw.core.audit.AuditEventRepository;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.domain.model.AuditEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AuditIdempotencyTest {

    @Inject
    AuditEventRepository repository;

    @Inject
    AuditService auditService;

    @Inject
    TelemetryAuditService telemetryAuditService;

    @Inject
    EntityManager em;

    @Test
    void testAuditEventIdempotency() {
        String sessionId = UUID.randomUUID().toString();
        String userId = "user1";
        String agentRole = "executor";
        SecurityEventType type = SecurityEventType.CHECKPOINT_CREATED;
        String idempotencyKey = "TEST_IDEMPOTENCY:" + sessionId;

        AuditEventEntity event1 = AuditEventEntity.securityEvent(sessionId, userId, agentRole, type, "attempt 1");
        event1.setIdempotencyKey(idempotencyKey);
        event1.setPrevHash("genesis");

        AuditEventEntity event2 = AuditEventEntity.securityEvent(sessionId, userId, agentRole, type, "attempt 2");
        event2.setIdempotencyKey(idempotencyKey);
        event2.setPrevHash("logic-would-be-ignored");

        // Record first one
        repository.record(event1);
        
        // Record second one (should be skipped)
        repository.record(event2);

        // Verify only one event exists for this session
        long count = em.createQuery(
                "SELECT COUNT(a) FROM AuditEvent a WHERE a.sessionId = :sid", Long.class)
            .setParameter("sid", sessionId)
            .getSingleResult();
        
        assertEquals(1, count, "Should have only one audit event due to idempotencyKey deduplication");
        
        AuditEvent recorded = em.createQuery(
                "SELECT a FROM AuditEvent a WHERE a.sessionId = :sid", AuditEventEntity.class)
            .setParameter("sid", sessionId)
            .getSingleResult();
            
        assertEquals("attempt 1", recorded.getEventDetail(), "Should have preserved the first attempt's detail");
    }

    @Test
    void testRetryDoesNotDuplicateAudit() throws InterruptedException {
        String sessionId = "retry-session-" + UUID.randomUUID();
        String idempotencyKey = "RETRY_IDEMPOTENCY:" + sessionId;
        
        // We will simulate what happens in StatefulAgentStoreImpl doSaveSteps
        // calling auditService.recordWithIdempotency() twice.
        
        auditService.recordWithIdempotency(sessionId, "u1", "r1", 
            SecurityEventType.CHECKPOINT_CREATED, "att 1", idempotencyKey);
            
        auditService.recordWithIdempotency(sessionId, "u1", "r1", 
            SecurityEventType.CHECKPOINT_CREATED, "att 2", idempotencyKey);
            
        // Wait for async persistence to catch up
        telemetryAuditService.waitForIdle();
        
        long count = em.createQuery(
                "SELECT COUNT(a) FROM AuditEvent a WHERE a.sessionId = :sid", Long.class)
            .setParameter("sid", sessionId)
            .getSingleResult();
            
        assertEquals(1, count, "Async audit recording should be idempotent even if multiple submissions occur");
    }
}
