package com.amsha.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.PersistenceAdapterPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;

@ExtendWith(MockitoExtension.class)
class AsyncPersistenceEngineUnitTest {

    @Mock
    MeterRegistry registry;

    @Mock
    PersistenceAdapterPort persistenceAdapter;

    @Mock
    CheckpointStorePort checkpointStore;

    @InjectMocks
    AsyncPersistenceEngine engine;

    @BeforeEach
    void setUp() {}

    @Test
    void testSubmitCriticalWrongTier() {
        PersistenceEvent event = PersistenceEvent.telemetry("s1", SecurityEventType.TOOL_EXECUTED, null);
        assertThrows(IllegalArgumentException.class, () -> engine.submitCritical(event));
    }

    @Test
    void testSubmitTelemetryWrongTier() {
        PersistenceEvent event = PersistenceEvent.critical("s1", SecurityEventType.TOOL_EXECUTED, null);
        assertThrows(IllegalArgumentException.class, () -> engine.submitTelemetry(event));
    }

    @Test
    void testWaitForIdleWhenNotRunning() {
        assertDoesNotThrow(() -> engine.waitForIdle());
    }
}
