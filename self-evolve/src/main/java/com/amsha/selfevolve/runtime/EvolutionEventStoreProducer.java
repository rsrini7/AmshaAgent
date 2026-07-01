package com.amsha.selfevolve.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.store.EvolutionEventStorePort;
import com.amsha.selfevolve.store.noop.NoOpEvolutionEventStore;

import org.jboss.logging.Logger;

/**
 * CDI producer for EvolutionEventStorePort.
 * Returns NoOp when self-evolve is disabled, otherwise resolves the real backend.
 */
@ApplicationScoped
public class EvolutionEventStoreProducer {

    private static final Logger log = Logger.getLogger(EvolutionEventStoreProducer.class);

    @Inject
    SelfEvolveConfig config;

    @Inject
    Instance<EvolutionEventStorePort> storeInstance;

    @Produces
    @ApplicationScoped
    public EvolutionEventStorePort resolveStore() {
        if (!config.enabled()) {
            log.debug("[SelfEvolve] Store disabled — returning NoOp");
            return new NoOpEvolutionEventStore();
        }
        // Resolve the real store from CDI (SqliteEvolutionEventStore is @ApplicationScoped)
        for (EvolutionEventStorePort candidate : storeInstance) {
            if (!(candidate instanceof NoOpEvolutionEventStore)) {
                log.infof(
                        "[SelfEvolve] Resolved store: %s", candidate.getClass().getSimpleName());
                return candidate;
            }
        }
        log.warn("[SelfEvolve] No real store found — falling back to NoOp");
        return new NoOpEvolutionEventStore();
    }
}
