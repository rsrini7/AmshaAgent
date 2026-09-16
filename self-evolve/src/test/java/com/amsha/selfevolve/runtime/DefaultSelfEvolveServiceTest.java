package com.amsha.selfevolve.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Iterator;
import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.extract.MemoryExtractorLite;
import com.amsha.selfevolve.store.DirectiveStorePort;
import com.amsha.selfevolve.store.EvolutionEventStorePort;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.runtime.ShutdownEvent;

@ExtendWith(MockitoExtension.class)
class DefaultSelfEvolveServiceTest {

    @Mock
    SelfEvolveConfig config;

    @Mock
    SelfEvolveConfig.FlushConfig flushConfig;

    @Mock
    EvolutionEventStorePort store;

    @Mock
    Instance<EvolutionEventStorePort> storeInstances;

    @Mock
    Instance<ExtractedMemoryStorePort> extractedStoreInstances;

    @Mock
    Instance<DirectiveStorePort> directiveStoreInstances;

    @Mock
    Instance<MemoryExtractorLite> extractorInstance;

    @InjectMocks
    DefaultSelfEvolveService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        lenient().when(config.flush()).thenReturn(flushConfig);
        // Mock Instance iteration to return our mock store
        Iterator<EvolutionEventStorePort> it = (Iterator<EvolutionEventStorePort>) Mockito.mock(Iterator.class);
        lenient().when(it.hasNext()).thenReturn(true, false);
        lenient().when(it.next()).thenReturn(store);
        lenient().when(storeInstances.iterator()).thenReturn(it);

        // Mock extracted store as unsatisfied (falls back to NoOp)
        lenient().when(extractorInstance.isUnsatisfied()).thenReturn(true);
        Iterator<ExtractedMemoryStorePort> extractedIt =
                (Iterator<ExtractedMemoryStorePort>) Mockito.mock(Iterator.class);
        lenient().when(extractedIt.hasNext()).thenReturn(false);
        lenient().when(extractedStoreInstances.iterator()).thenReturn(extractedIt);

        // Mock directive store instances
        Iterator<DirectiveStorePort> directiveIt = (Iterator<DirectiveStorePort>) Mockito.mock(Iterator.class);
        lenient().when(directiveIt.hasNext()).thenReturn(false);
        lenient().when(directiveStoreInstances.iterator()).thenReturn(directiveIt);
    }

    @Test
    void emit_disabled_doesNothing() {
        when(config.enabled()).thenReturn(false);
        service.init();
        boolean flushed = service.emit("s1", "u1", EvolutionEventType.USER_MESSAGE, "coordinator", "hi", null, 1);
        assertFalse(flushed);
        verify(store, never()).insertBatch(any());
    }

    @Test
    void emit_enabled_persistsOnThreshold() {
        when(config.enabled()).thenReturn(true);
        when(config.bufferSize()).thenReturn(2);
        when(flushConfig.maxEvents()).thenReturn(2);
        when(flushConfig.idleMs()).thenReturn(300000L);
        when(store.insertBatch(any())).thenReturn(2);

        service.init();

        service.emit("s1", "u1", EvolutionEventType.USER_MESSAGE, "coordinator", "msg1", null, 1);
        boolean flushed = service.emit("s1", "u1", EvolutionEventType.AGENT_MESSAGE, "coordinator", "msg2", null, 1);

        assertTrue(flushed);
        verify(store, times(1)).insertBatch(any());
    }

    @Test
    void emit_enabled_noFlushBelowThreshold() {
        when(config.enabled()).thenReturn(true);
        when(config.bufferSize()).thenReturn(10);
        when(flushConfig.maxEvents()).thenReturn(10);
        when(flushConfig.idleMs()).thenReturn(300000L);

        service.init();

        boolean flushed = service.emit("s1", "u1", EvolutionEventType.USER_MESSAGE, "coordinator", "msg1", null, 1);
        assertFalse(flushed);
        verify(store, never()).insertBatch(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushSession_drainsAndPersists() {
        when(config.enabled()).thenReturn(true);
        when(config.bufferSize()).thenReturn(10);
        when(flushConfig.maxEvents()).thenReturn(10);
        when(flushConfig.idleMs()).thenReturn(300000L);
        when(store.insertBatch(any())).thenReturn(1);

        service.init();

        service.emit("s1", "u1", EvolutionEventType.TOOL_CALL, "coder", "exec code", "sandbox_exec", 1);
        service.flushSession("s1");

        ArgumentCaptor<List<EvolutionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(store, times(1)).insertBatch(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("sandbox_exec", captor.getValue().get(0).toolName());
    }

    @Test
    void failedThresholdFlush_restoresEventsForLaterPersistence() {
        when(config.enabled()).thenReturn(true);
        when(config.bufferSize()).thenReturn(2);
        when(flushConfig.maxEvents()).thenReturn(2);
        when(flushConfig.idleMs()).thenReturn(300000L);
        doThrow(new IllegalStateException("pool closed")).when(store).insertBatch(any());

        service.init();
        service.emit("s1", "u1", EvolutionEventType.USER_MESSAGE, "coordinator", "one", null, 1);
        service.emit("s1", "u1", EvolutionEventType.AGENT_MESSAGE, "coordinator", "two", null, 1);

        assertEquals(2, service.bufferSize("s1"));
        doReturn(2).when(store).insertBatch(any());
        service.flushSession("s1");

        assertEquals(0, service.bufferSize("s1"));
        verify(store, times(2)).insertBatch(any());
    }

    @Test
    void shutdown_flushesRemainingEventsBeforePersistenceIsClosed() {
        when(config.enabled()).thenReturn(true);
        when(config.bufferSize()).thenReturn(10);
        when(flushConfig.maxEvents()).thenReturn(10);
        when(flushConfig.idleMs()).thenReturn(300000L);
        when(store.insertBatch(any())).thenReturn(1);

        service.init();
        service.emit("s1", "u1", EvolutionEventType.USER_MESSAGE, "coordinator", "one", null, 1);
        service.onShutdown(new ShutdownEvent());

        verify(store).insertBatch(any());
    }

    @Test
    void isEnabled_returnsConfigValue() {
        when(config.enabled()).thenReturn(false);
        assertFalse(service.isEnabled());
        when(config.enabled()).thenReturn(true);
        assertTrue(service.isEnabled());
    }

    @Test
    void bufferSize_returnsZeroWhenNoBuffer() {
        assertEquals(0, service.bufferSize("unknown"));
    }
}
