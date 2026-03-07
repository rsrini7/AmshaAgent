package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;

@ExtendWith(MockitoExtension.class)
class ComplianceAuditServiceTest {

    @Mock
    AuditEventRepository auditEventRepository;

    @Mock
    AsyncPersistenceEngine persistenceEngine;

    ComplianceAuditService complianceAuditService;

    @BeforeEach
    void setUp() {
        complianceAuditService = new ComplianceAuditService(auditEventRepository, persistenceEngine);
    }

    @Test
    void testRecordMandatorySucceeds() {
        // persistenceEngine.submitCritical does nothing (completes fine)
        doNothing().when(persistenceEngine).submitCritical(any());

        // Should not throw
        assertDoesNotThrow(() ->
                complianceAuditService.recordMandatory("ses-1", "u1", "coordinator",
                        SecurityEventType.TOOL_INVOKED, "test"));
    }

    @Test
    void testRecordMandatoryThrowsOnWriteFailure() {
        // Simulate the engine throwing ComplianceViolationException
        doThrow(new ComplianceViolationException("ses-2", SecurityEventType.VAULT_ACCESS,
                        new RuntimeException("SQLITE_BUSY: database is locked")))
            .when(persistenceEngine).submitCritical(any());

        ComplianceViolationException ex = assertThrows(ComplianceViolationException.class, () ->
                complianceAuditService.recordMandatory("ses-2", "u1", "coordinator",
                        SecurityEventType.VAULT_ACCESS, "vault read"));
        assertEquals("ses-2", ex.getSessionId());
        assertEquals(SecurityEventType.VAULT_ACCESS, ex.getEventType());
    }

    @Test
    void testRecordBuildsCorrectCriticalEvent() {
        doNothing().when(persistenceEngine).submitCritical(any());

        complianceAuditService.recordMandatory("ses-3", "u999", "coder",
                SecurityEventType.PROMPT_INJECTION_BLOCKED, "injection detected");

        ArgumentCaptor<PersistenceEvent> captor = ArgumentCaptor.forClass(PersistenceEvent.class);
        verify(persistenceEngine).submitCritical(captor.capture());

        PersistenceEvent captured = captor.getValue();
        assertEquals(PersistenceEvent.Tier.CRITICAL, captured.getTier());
        assertEquals("ses-3", captured.getSessionId());
        assertEquals(SecurityEventType.PROMPT_INJECTION_BLOCKED, captured.getEventType());
    }
}
