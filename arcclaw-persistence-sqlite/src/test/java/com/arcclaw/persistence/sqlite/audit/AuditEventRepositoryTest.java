package com.arcclaw.persistence.sqlite.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditEventEntity;
import com.arcclaw.core.audit.AuditEventRepository;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuditEventRepositoryTest {

    @Inject
    AuditEventRepository repository;

    @Inject
    AuditService auditService;

    @Inject
    AuditHashUtil hashUtil;

    @BeforeEach
    void setup() {
        auditService.waitForIdle();
    }

    @Test
    void testPersistAndFind() {
        String sessionId = "audit-" + UUID.randomUUID();
        
        AuditEventEntity event = AuditEventEntity.of(
            sessionId,
            "user-1",
            "coordinator",
            "goal-hash",
            AuditOutcome.FINISH,
            SecurityEventType.CHECKPOINT_CREATED,
            0, 0, BigDecimal.ZERO, 0,
            "demo"
        );
        event.setPrevHash("prev");
        event.setHmacHash("hmac");

        repository.record(event);
        auditService.waitForIdle();

        var list = repository.findBySessionId(sessionId);
        assertEquals(1, list.size());
        assertEquals(SecurityEventType.CHECKPOINT_CREATED, list.get(0).getEventType());
    }

    @Test
    void testValidation_rejectsNullSessionId() {
        AuditEventEntity event = new AuditEventEntity();
        Assertions.assertThrows(IllegalStateException.class,
            () -> repository.validateBeforePersist(event));
    }
}
