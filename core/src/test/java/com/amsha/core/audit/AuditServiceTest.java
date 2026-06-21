package com.amsha.core.audit;

import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.persistence.api.AuditEventStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    TelemetryAuditService telemetryAuditService;

    @Mock
    ComplianceAuditService complianceAuditService;

    @Mock
    AuditEventStorePort auditStore;

    AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(telemetryAuditService, complianceAuditService, auditStore);
    }

    @Test
    void testRecordDelegates() {
        auditService.record("ses-1", "u1", "role", SecurityEventType.TOOL_INVOKED, "detail");
        verify(telemetryAuditService).record("ses-1", "u1", "role", SecurityEventType.TOOL_INVOKED, "detail");
    }

    @Test
    void testRecordLoopCompletionDelegates() {
        AgentResult result = AgentResult.finished("done", List.of());
        BigDecimal cost = new BigDecimal("0.01");
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
