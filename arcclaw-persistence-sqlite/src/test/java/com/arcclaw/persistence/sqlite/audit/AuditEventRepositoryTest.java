package com.arcclaw.persistence.sqlite.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditEventRecorder;
import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.AuditOutcome;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.persistence.api.AuditEventStorePort;
import com.arcclaw.persistence.common.entity.AuditEventEntity;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuditEventRepositoryTest {

    @Inject
    AuditEventRecorder recorder;

    @Inject
    AuditEventStorePort storePort;

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

        recorder.record(event);
        auditService.waitForIdle();

        var list = storePort.findBySessionId(sessionId);
        assertEquals(1, list.size());
        assertEquals(SecurityEventType.CHECKPOINT_CREATED.name(), list.get(0).eventType());
    }

    @Test
    void testValidation_rejectsNullSessionId() {
        AuditEventEntity event = new AuditEventEntity();
        Assertions.assertThrows(IllegalStateException.class,
            () -> recorder.validateBeforePersist(event));
    }
}
