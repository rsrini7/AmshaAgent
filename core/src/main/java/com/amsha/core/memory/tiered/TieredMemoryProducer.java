package com.amsha.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.persistence.api.TieredBackend;

/**
 * CDI producer that resolves the active tiered memory backends once at startup.
 * <p>
 * Services inject the segregated ports directly — zero lookup overhead on the hot path:
 * <pre>{@code
 *   @Inject CoreMemoryStorePort coreStore;
 *   @Inject RecallMemoryStorePort recallStore;
 *   @Inject ArchivalMemoryStorePort archivalStore;
 * }</pre>
 * <p>
 * Backend implementations must be annotated with {@code @TieredBackend("sqlite")},
 * {@code @TieredBackend("postgres")}, or {@code @TieredBackend("fs")}.
 * When tiered memory or an individual tier is disabled, producers return safe no-op
 * implementations.
 */
@ApplicationScoped
public class TieredMemoryProducer {

    @Inject
    TieredMemoryConfig config;

    @Inject
    @Any
    Instance<CoreMemoryStorePort> coreStores;

    @Inject
    @Any
    Instance<RecallMemoryStorePort> recallStores;

    @Inject
    @Any
    Instance<ArchivalMemoryStorePort> archivalStores;

    @Produces
    @ApplicationScoped
    public CoreMemoryStorePort coreStore() {
        if (!config.enabled() || !config.coreMemory().enabled()) {
            return new NoOpCoreMemoryStore();
        }
        return resolve(coreStores, config.backend(), "CoreMemoryStorePort");
    }

    @Produces
    @ApplicationScoped
    public RecallMemoryStorePort recallStore() {
        if (!config.enabled() || !config.recall().enabled()) {
            return new NoOpRecallMemoryStore();
        }
        return resolve(recallStores, config.backend(), "RecallMemoryStorePort");
    }

    @Produces
    @ApplicationScoped
    public ArchivalMemoryStorePort archivalStore() {
        if (!config.enabled() || !config.archival().enabled()) {
            return new NoOpArchivalMemoryStore();
        }
        return resolve(archivalStores, config.backend(), "ArchivalMemoryStorePort");
    }

    private <T> T resolve(Instance<T> instances, String backendId, String portName) {
        TieredMemoryBackend backend = TieredMemoryBackend.fromId(backendId);
        Instance<T> matched = instances.select(new TieredBackendLiteral(backend.id()));

        if (matched.isUnsatisfied()) {
            throw new IllegalStateException("No @" + TieredBackend.class.getSimpleName() + "(\"" + backend.id() + "\") "
                    + portName + " implementation found on the classpath.");
        }

        if (matched.isAmbiguous()) {
            throw new IllegalStateException("Multiple @" + TieredBackend.class.getSimpleName() + "(\"" + backend.id()
                    + "\") " + portName + " implementations found.");
        }

        return matched.get();
    }
}
