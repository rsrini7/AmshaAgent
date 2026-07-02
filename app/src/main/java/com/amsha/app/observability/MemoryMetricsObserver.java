package com.amsha.app.observability;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer metrics observer for Tiered Memory operations.
 * <p>
 * Provides observability for recall search latency, archival confidence distribution,
 * and memory operation counters. These metrics enable production monitoring of
 * memory tier performance and health.
 */
@ApplicationScoped
public class MemoryMetricsObserver {

    private static final Logger log = Logger.getLogger(MemoryMetricsObserver.class);

    private static final String TAG_TIER = "tier";

    @Inject
    MeterRegistry registry;

    // Counters
    private Counter recallSearchCounter;
    private Counter archivalSearchCounter;
    private Counter recallPurgeCounter;
    private Counter archivalEvictCounter;

    // Timers
    private Timer recallSearchLatency;
    private Timer archivalSearchLatency;

    // Distribution summaries
    private DistributionSummary archivalConfidenceDistribution;
    private DistributionSummary recallResultCount;
    private DistributionSummary archivalResultCount;

    @PostConstruct
    public void init() {
        recallSearchCounter = Counter.builder("memory.recall.search")
                .tag(TAG_TIER, "recall")
                .description("Total recall memory searches")
                .register(registry);

        archivalSearchCounter = Counter.builder("memory.archival.search")
                .tag(TAG_TIER, "archival")
                .description("Total archival memory searches")
                .register(registry);

        recallPurgeCounter = Counter.builder("memory.recall.purge")
                .tag(TAG_TIER, "recall")
                .description("Total recall memory purge operations")
                .register(registry);

        archivalEvictCounter = Counter.builder("memory.archival.evict")
                .tag(TAG_TIER, "archival")
                .description("Total archival memory eviction operations")
                .register(registry);

        recallSearchLatency = Timer.builder("memory.recall.search.latency")
                .tag(TAG_TIER, "recall")
                .description("Recall memory search latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        archivalSearchLatency = Timer.builder("memory.archival.search.latency")
                .tag(TAG_TIER, "archival")
                .description("Archival memory search latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        archivalConfidenceDistribution = DistributionSummary.builder("memory.archival.confidence")
                .tag(TAG_TIER, "archival")
                .description("Distribution of archival chunk confidence scores")
                .publishPercentiles(0.1, 0.25, 0.5, 0.75, 0.9)
                .register(registry);

        recallResultCount = DistributionSummary.builder("memory.recall.result.count")
                .tag(TAG_TIER, "recall")
                .description("Distribution of recall search result counts")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        archivalResultCount = DistributionSummary.builder("memory.archival.result.count")
                .tag(TAG_TIER, "archival")
                .description("Distribution of archival search result counts")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        log.info("[MemoryMetrics] Initialized Micrometer metrics for tiered memory");
    }

    /**
     * Record a recall search operation with latency and result count.
     */
    public void recordRecallSearch(long latencyNanos, int resultCount) {
        recallSearchCounter.increment();
        recallSearchLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
        recallResultCount.record(resultCount);
    }

    /**
     * Record an archival search operation with latency, result count, and confidence distribution.
     */
    public void recordArchivalSearch(long latencyNanos, int resultCount, double avgConfidence) {
        archivalSearchCounter.increment();
        archivalSearchLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
        archivalResultCount.record(resultCount);
        if (avgConfidence > 0) {
            archivalConfidenceDistribution.record(avgConfidence);
        }
    }

    /**
     * Record a recall purge operation.
     */
    public void recordRecallPurge() {
        recallPurgeCounter.increment();
    }

    /**
     * Record an archival eviction operation.
     */
    public void recordArchivalEvict() {
        archivalEvictCounter.increment();
    }
}
