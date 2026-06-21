package com.amsha.persistence.sqlite.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import java.util.UUID;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AuditEventRepositoryTest {

    @Inject
    AuditEventStorePort storePort;

    @Inject
    AuditService auditService;

    @BeforeEach
    void setup() {
        auditService.waitForIdle();
    }

    @Test
    void testPersistAndFind() {
        String sessionId = "audit-" + UUID.randomUUID();

        auditService.record(sessionId, "user-1", "coordinator", SecurityEventType.CHECKPOINT_CREATED, "demo");
        auditService.waitForIdle();

        var list = storePort.findBySessionId(sessionId);
        assertEquals(1, list.size());
        assertEquals(SecurityEventType.CHECKPOINT_CREATED.name(), list.get(0).eventType());
    }
}
