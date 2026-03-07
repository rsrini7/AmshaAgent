package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.core.persistence.AsyncPersistenceEngine;
import com.arcclaw.core.persistence.PersistenceEvent;
import com.arcclaw.core.config.SystemMetadataRegistry;

@ExtendWith(MockitoExtension.class)
class TelemetryAuditServiceTest {

    @Mock
    AuditEventRepository auditEventRepository;

    @Mock
    AsyncPersistenceEngine persistenceEngine;

    @Mock
    SystemMetadataRegistry registry;

    TelemetryAuditService telemetryAuditService;

    @BeforeEach
    void setUp() {
        telemetryAuditService = new TelemetryAuditService(auditEventRepository, persistenceEngine, registry);
    }

    @Test
    void testRecordIsTelemetryTier() {
        doNothing().when(persistenceEngine).submitTelemetry(any());
        when(registry.getAppVersion()).thenReturn("1.0.0");

        telemetryAuditService.record("ses-4", "u1", "researcher", SecurityEventType.REFLEX_COMMAND, "halt");

        ArgumentCaptor<PersistenceEvent> captor = ArgumentCaptor.forClass(PersistenceEvent.class);
        verify(persistenceEngine).submitTelemetry(captor.capture());
        assertEquals(PersistenceEvent.Tier.TELEMETRY, captor.getValue().getTier());

        // Capture the event created inside the callback
        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        captor.getValue().getDbAction().accept(null); // Execute the lambda
        verify(auditEventRepository).record(eventCaptor.capture());
        assertEquals("1.0.0", eventCaptor.getValue().appVersion);
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
