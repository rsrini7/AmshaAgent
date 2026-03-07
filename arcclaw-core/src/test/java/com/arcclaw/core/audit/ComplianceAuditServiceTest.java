package com.arcclaw.core.audit;

import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceAuditServiceTest {

    @Mock
    AuditEventRepository auditEventRepository;

    @Mock
    AsyncPersistenceEngine persistenceEngine;

    @InjectMocks
    ComplianceAuditService complianceAuditService;

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
