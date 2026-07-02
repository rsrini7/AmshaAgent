package com.amsha.app.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MemoryMetricsObserverTest {

    MeterRegistry registry;
    MemoryMetricsObserver observer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observer = new MemoryMetricsObserver();
        setField(observer, "registry", registry);
        observer.init();
    }

    @Test
    void recallSearchIncrementsCounter() {
        observer.recordRecallSearch(1_000_000, 5);

        Counter counter = registry.find("memory.recall.search").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recallSearchRecordsLatency() {
        observer.recordRecallSearch(5_000_000, 3);

        Timer timer = registry.find("memory.recall.search.latency").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
    }

    @Test
    void recallSearchRecordsResultCount() {
        observer.recordRecallSearch(1_000_000, 10);

        DistributionSummary summary =
                registry.find("memory.recall.result.count").summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
        assertEquals(10.0, summary.totalAmount());
    }

    @Test
    void archivalSearchIncrementsCounter() {
        observer.recordArchivalSearch(1_000_000, 5, 0.8);

        Counter counter = registry.find("memory.archival.search").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void archivalSearchRecordsLatency() {
        observer.recordArchivalSearch(5_000_000, 3, 0.7);

        Timer timer = registry.find("memory.archival.search.latency").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
    }

    @Test
    void archivalSearchRecordsConfidenceDistribution() {
        observer.recordArchivalSearch(1_000_000, 5, 0.85);

        DistributionSummary summary =
                registry.find("memory.archival.confidence").summary();
        assertNotNull(summary);
        assertEquals(1, summary.count());
        assertEquals(0.85, summary.totalAmount());
    }

    @Test
    void archivalSearchSkipsConfidenceWhenZero() {
        observer.recordArchivalSearch(1_000_000, 5, 0.0);

        DistributionSummary summary =
                registry.find("memory.archival.confidence").summary();
        assertNotNull(summary);
        assertEquals(0, summary.count());
    }

    @Test
    void recallPurgeIncrementsCounter() {
        observer.recordRecallPurge();

        Counter counter = registry.find("memory.recall.purge").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void archivalEvictIncrementsCounter() {
        observer.recordArchivalEvict();

        Counter counter = registry.find("memory.archival.evict").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void multipleOperationsAccumulate() {
        observer.recordRecallSearch(1_000_000, 5);
        observer.recordRecallSearch(2_000_000, 3);
        observer.recordRecallSearch(3_000_000, 7);

        Counter counter = registry.find("memory.recall.search").counter();
        assertNotNull(counter);
        assertEquals(3.0, counter.count());

        Timer timer = registry.find("memory.recall.search.latency").timer();
        assertNotNull(timer);
        assertEquals(3, timer.count());
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
