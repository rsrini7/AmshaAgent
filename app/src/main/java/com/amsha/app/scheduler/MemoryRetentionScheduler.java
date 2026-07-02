package com.amsha.app.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.core.memory.tiered.ArchivalMemoryService;
import com.amsha.core.memory.tiered.RecallMemoryService;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled jobs for memory retention and eviction.
 * <p>
 * Purges expired recall messages and evicts low-confidence archival chunks
 * on a configurable schedule. These jobs ensure memory hygiene without
 * manual intervention.
 */
@ApplicationScoped
public class MemoryRetentionScheduler {

    private static final Logger log = Logger.getLogger(MemoryRetentionScheduler.class);

    @Inject
    RecallMemoryService recallService;

    @Inject
    ArchivalMemoryService archivalService;

    @Inject
    TieredMemoryConfig tieredConfig;

    @ConfigProperty(name = "amsha.memory.retention.recall-retention-days", defaultValue = "30")
    int recallRetentionDays;

    @ConfigProperty(name = "amsha.memory.retention.archival-eviction-threshold", defaultValue = "0.3")
    double archivalEvictionThreshold;

    @ConfigProperty(name = "amsha.memory.retention.archival-min-age-days", defaultValue = "30")
    int archivalMinAgeDays;

    @ConfigProperty(name = "amsha.memory.retention.enabled", defaultValue = "true")
    boolean retentionEnabled;

    /**
     * Purge expired recall messages every day at 2:00 AM.
     * Messages older than recallRetentionDays are deleted.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    void purgeExpiredRecallMessages() {
        if (!retentionEnabled
                || !tieredConfig.enabled()
                || !tieredConfig.recall().enabled()) {
            log.debug("[MemoryRetention] Recall purge skipped — retention or tiered memory disabled");
            return;
        }

        try {
            log.infof("[MemoryRetention] Starting recall purge (retentionDays=%d)", recallRetentionDays);
            recallService.purgeExpiredAll(recallRetentionDays);
            log.info("[MemoryRetention] Recall purge completed");
        } catch (Exception e) {
            log.errorf("[MemoryRetention] Recall purge failed: %s", e.getMessage());
        }
    }

    /**
     * Evict low-confidence archival chunks every day at 3:00 AM.
     * Chunks with confidence below threshold and older than minAgeDays are evicted.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    void evictLowConfidenceArchivalChunks() {
        if (!retentionEnabled
                || !tieredConfig.enabled()
                || !tieredConfig.archival().enabled()) {
            log.debug("[MemoryRetention] Archival eviction skipped — retention or tiered memory disabled");
            return;
        }

        try {
            log.infof(
                    "[MemoryRetention] Starting archival eviction (threshold=%.2f, minAgeDays=%d)",
                    archivalEvictionThreshold, archivalMinAgeDays);
            archivalService.evictLowConfidence(archivalEvictionThreshold, archivalMinAgeDays);
            log.info("[MemoryRetention] Archival eviction completed");
        } catch (Exception e) {
            log.errorf("[MemoryRetention] Archival eviction failed: %s", e.getMessage());
        }
    }
}
