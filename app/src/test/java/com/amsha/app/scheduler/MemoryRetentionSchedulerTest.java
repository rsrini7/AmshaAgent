package com.amsha.app.scheduler;

import static org.mockito.Mockito.*;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.core.memory.tiered.ArchivalMemoryService;
import com.amsha.core.memory.tiered.RecallMemoryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemoryRetentionSchedulerTest {

    @Mock
    RecallMemoryService recallService;

    @Mock
    ArchivalMemoryService archivalService;

    @Mock
    TieredMemoryConfig tieredConfig;

    @Mock
    TieredMemoryConfig.RecallConfig recallConfig;

    @Mock
    TieredMemoryConfig.ArchivalConfig archivalConfig;

    @InjectMocks
    MemoryRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MemoryRetentionScheduler();
        setField(scheduler, "recallService", recallService);
        setField(scheduler, "archivalService", archivalService);
        setField(scheduler, "tieredConfig", tieredConfig);
        setField(scheduler, "retentionEnabled", true);
        setField(scheduler, "recallRetentionDays", 30);
        setField(scheduler, "archivalEvictionThreshold", 0.3);
        setField(scheduler, "archivalMinAgeDays", 30);

        lenient().when(tieredConfig.recall()).thenReturn(recallConfig);
        lenient().when(tieredConfig.archival()).thenReturn(archivalConfig);
    }

    @Test
    void purgeExpiredRecallMessagesWhenEnabled() {
        when(tieredConfig.enabled()).thenReturn(true);
        when(recallConfig.enabled()).thenReturn(true);

        scheduler.purgeExpiredRecallMessages();

        verify(recallService).purgeExpiredAll(30);
    }

    @Test
    void purgeExpiredRecallMessagesSkippedWhenDisabled() {
        setField(scheduler, "retentionEnabled", false);

        scheduler.purgeExpiredRecallMessages();

        verifyNoInteractions(recallService);
    }

    @Test
    void purgeExpiredRecallMessagesSkippedWhenTieredDisabled() {
        when(tieredConfig.enabled()).thenReturn(false);

        scheduler.purgeExpiredRecallMessages();

        verifyNoInteractions(recallService);
    }

    @Test
    void purgeExpiredRecallMessagesSkippedWhenRecallDisabled() {
        when(tieredConfig.enabled()).thenReturn(true);
        when(recallConfig.enabled()).thenReturn(false);

        scheduler.purgeExpiredRecallMessages();

        verifyNoInteractions(recallService);
    }

    @Test
    void evictLowConfidenceArchivalChunksWhenEnabled() {
        when(tieredConfig.enabled()).thenReturn(true);
        when(archivalConfig.enabled()).thenReturn(true);

        scheduler.evictLowConfidenceArchivalChunks();

        verify(archivalService).evictLowConfidence(0.3, 30);
    }

    @Test
    void evictLowConfidenceArchivalChunksSkippedWhenDisabled() {
        setField(scheduler, "retentionEnabled", false);

        scheduler.evictLowConfidenceArchivalChunks();

        verifyNoInteractions(archivalService);
    }

    @Test
    void evictLowConfidenceArchivalChunksSkippedWhenTieredDisabled() {
        when(tieredConfig.enabled()).thenReturn(false);

        scheduler.evictLowConfidenceArchivalChunks();

        verifyNoInteractions(archivalService);
    }

    @Test
    void evictLowConfidenceArchivalChunksSkippedWhenArchivalDisabled() {
        when(tieredConfig.enabled()).thenReturn(true);
        when(archivalConfig.enabled()).thenReturn(false);

        scheduler.evictLowConfidenceArchivalChunks();

        verifyNoInteractions(archivalService);
    }

    @Test
    void purgeHandlesExceptionGracefully() {
        when(tieredConfig.enabled()).thenReturn(true);
        when(recallConfig.enabled()).thenReturn(true);
        doThrow(new RuntimeException("Test exception")).when(recallService).purgeExpiredAll(anyInt());

        scheduler.purgeExpiredRecallMessages();

        verify(recallService).purgeExpiredAll(30);
    }

    @Test
    void evictHandlesExceptionGracefully() {
        when(tieredConfig.enabled()).thenReturn(true);
        when(archivalConfig.enabled()).thenReturn(true);
        doThrow(new RuntimeException("Test exception")).when(archivalService).evictLowConfidence(anyDouble(), anyInt());

        scheduler.evictLowConfidenceArchivalChunks();

        verify(archivalService).evictLowConfidence(0.3, 30);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
