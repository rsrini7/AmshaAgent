package com.arcclaw.core.audit;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.core.agent.result.AgentResult;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    TelemetryAuditService telemetryAuditService;

    @Mock
    ComplianceAuditService complianceAuditService;

    @InjectMocks
    AuditService auditService;

    @Test
    void testRecordDelegates() {
        auditService.record("ses-1", "u1", "role", SecurityEventType.TOOL_INVOKED, "detail");
        verify(telemetryAuditService).record("ses-1", "u1", "role", SecurityEventType.TOOL_INVOKED, "detail");
    }

    @Test
    void testRecordLoopCompletionDelegates() {
        AgentResult result = AgentResult.finished("done", java.util.List.of());
        java.math.BigDecimal cost = new java.math.BigDecimal("0.01");
        auditService.recordLoopCompletion("ses", "u", "r", "hash", result, 10, 20, cost);
        verify(telemetryAuditService).recordLoopCompletion("ses", "u", "r", "hash", result, 10, 20, cost);
    }
    
    @Test
    void testRecordMandatoryDelegates() {
        auditService.recordMandatory("ses-1", "u1", "role", SecurityEventType.TOOL_INVOKED, "detail");
        verify(complianceAuditService).recordMandatory("ses-1", "u1", "role", SecurityEventType.TOOL_INVOKED, "detail");
    }

    @Test
    void testWaitForIdleDelegates() {
        auditService.waitForIdle();
        verify(telemetryAuditService).waitForIdle();
    }
}
