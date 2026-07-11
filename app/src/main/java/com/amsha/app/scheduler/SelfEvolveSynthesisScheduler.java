package com.amsha.app.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.common.util.LogUtil;
import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.policy.InteractionPolicySynthesizer;
import com.amsha.selfevolve.policy.ShadowLifecycleManager;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/**
 * Periodic self-evolve synthesis pass.
 * Runs out-of-band only when synthesis trigger is configured as {@code cron}.
 */
@ApplicationScoped
public class SelfEvolveSynthesisScheduler {

    private static final Logger log = Logger.getLogger(SelfEvolveSynthesisScheduler.class);

    private static final int MAX_USERS_PER_RUN = 200;

    @Inject
    SelfEvolveConfig config;

    @Inject
    ExtractedMemoryStorePort extractedMemoryStore;

    @Inject
    InteractionPolicySynthesizer synthesizer;

    @Inject
    ShadowLifecycleManager lifecycleManager;

    @Scheduled(cron = "0 0 4 * * ?")
    void runDailySynthesis() {
        if (!config.enabled() || !config.directivesEnabled() || !"cron".equalsIgnoreCase(config.synthesisTrigger())) {
            log.debug("[SelfEvolveScheduler] Skipping synthesis - disabled or trigger != cron");
            return;
        }

        try {
            List<String> userIds = extractedMemoryStore.listUserIds(MAX_USERS_PER_RUN);
            int synthesizedUsers = 0;
            int directivesCreated = 0;
            for (String userId : userIds) {
                if (userId == null || userId.isBlank()) {
                    continue;
                }
                try {
                    int created = synthesizer.synthesize(userId).size();
                    if (created > 0) {
                        synthesizedUsers++;
                        directivesCreated += created;
                    }
                } catch (Exception e) {
                    log.errorf(
                            "[SelfEvolveScheduler] Synthesis failed for user %s: %s", userId, LogUtil.rootMessage(e));
                }
            }
            int lifecycleActions = lifecycleManager.reconcileAll();
            log.infof(
                    "[SelfEvolveScheduler] Daily synthesis complete: users=%d, directives=%d, lifecycleActions=%d",
                    synthesizedUsers, directivesCreated, lifecycleActions);
        } catch (Exception e) {
            log.errorf("[SelfEvolveScheduler] Daily synthesis failed: %s", LogUtil.rootMessage(e));
        }
    }
}
