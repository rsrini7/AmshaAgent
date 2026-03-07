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
class TelemetryAuditServiceTest {

    @Mock
    AuditEventRepository auditEventRepository;

    @Mock
    AsyncPersistenceEngine persistenceEngine;

    @InjectMocks
    TelemetryAuditService telemetryAuditService;

    @Test
    void testRecordIsTelemetryTier() {
        doNothing().when(persistenceEngine).submitTelemetry(any());

        telemetryAuditService.record("ses-4", "u1", "researcher", SecurityEventType.REFLEX_COMMAND, "halt");

        ArgumentCaptor<PersistenceEvent> captor = ArgumentCaptor.forClass(PersistenceEvent.class);
        verify(persistenceEngine).submitTelemetry(captor.capture());
        assertEquals(PersistenceEvent.Tier.TELEMETRY, captor.getValue().getTier());
    }

    @Test
    void testRecordSwallowsExceptions() {
        doThrow(new RuntimeException("[TEST-EXPECTED] mock telemetry failure")).when(persistenceEngine).submitTelemetry(any());

        // Must not throw — caller must not be affected by TELEMETRY failures
        assertDoesNotThrow(() ->
                telemetryAuditService.record("ses-5", "u1", "debugger",
                        SecurityEventType.CONTEXT_COMPACTED, "compacted"));
    }
}
