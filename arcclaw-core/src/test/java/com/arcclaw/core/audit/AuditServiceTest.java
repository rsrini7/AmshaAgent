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
class AuditServiceTest {

    @Mock
    AuditEventRepository auditEventRepository;

    @Mock
    AsyncPersistenceEngine persistenceEngine;

    @InjectMocks
    AuditService auditService;

    @Test
    void testRecordMandatorySucceeds() {
        // persistenceEngine.submitCritical does nothing (completes fine)
        doNothing().when(persistenceEngine).submitCritical(any());

        // Should not throw
        assertDoesNotThrow(() ->
                auditService.recordMandatory("ses-1", "u1", "coordinator",
                        SecurityEventType.TOOL_INVOKED, "test"));
    }

    @Test
    void testRecordMandatoryThrowsOnWriteFailure() {
        // Simulate the engine throwing ComplianceViolationException
        doThrow(new ComplianceViolationException("ses-2", SecurityEventType.VAULT_ACCESS,
                        new RuntimeException("SQLITE_BUSY: database is locked")))
            .when(persistenceEngine).submitCritical(any());

        ComplianceViolationException ex = assertThrows(ComplianceViolationException.class, () ->
                auditService.recordMandatory("ses-2", "u1", "coordinator",
                        SecurityEventType.VAULT_ACCESS, "vault read"));
        assertEquals("ses-2", ex.getSessionId());
        assertEquals(SecurityEventType.VAULT_ACCESS, ex.getEventType());
    }

    @Test
    void testRecordBuildsCorrectCriticalEvent() {
        doNothing().when(persistenceEngine).submitCritical(any());

        auditService.recordMandatory("ses-3", "u999", "coder",
                SecurityEventType.PROMPT_INJECTION_BLOCKED, "injection detected");

        ArgumentCaptor<PersistenceEvent> captor = ArgumentCaptor.forClass(PersistenceEvent.class);
        verify(persistenceEngine).submitCritical(captor.capture());

        PersistenceEvent captured = captor.getValue();
        assertEquals(PersistenceEvent.Tier.CRITICAL, captured.getTier());
        assertEquals("ses-3", captured.getSessionId());
        assertEquals(SecurityEventType.PROMPT_INJECTION_BLOCKED, captured.getEventType());
    }

    @Test
    void testRecordIsTelemetryTier() {
        doNothing().when(persistenceEngine).submitTelemetry(any());

        auditService.record("ses-4", "u1", "researcher", SecurityEventType.REFLEX_COMMAND, "halt");

        ArgumentCaptor<PersistenceEvent> captor = ArgumentCaptor.forClass(PersistenceEvent.class);
        verify(persistenceEngine).submitTelemetry(captor.capture());
        assertEquals(PersistenceEvent.Tier.TELEMETRY, captor.getValue().getTier());
    }

    @Test
    void testRecordSwallowsExceptions() {
        doThrow(new RuntimeException("mock telemetry failure")).when(persistenceEngine).submitTelemetry(any());

        // Must not throw — caller must not be affected by TELEMETRY failures
        assertDoesNotThrow(() ->
                auditService.record("ses-5", "u1", "debugger",
                        SecurityEventType.CONTEXT_COMPACTED, "compacted"));
    }
}
