package com.arcclaw.app.config;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import com.arcclaw.core.tools.ToolCategory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Produces dedicated ManagedExecutor pools per tool category (§T5-2).
 * Isolates Network, Compute, Database, and Internal tools into
 * separate thread pools to prevent starvation across categories.
 * <p>
 * Uses @Identifier instead of @Named to avoid implicit @Default qualifier
 * which causes ambiguous dependency errors in core modules (§P84 fix).
 */
@ApplicationScoped
public class ToolExecutorProducer {

    @Inject
    MeterRegistry meterRegistry;

    @Produces
    @ApplicationScoped
    @Identifier("network-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor networkToolExecutor(
            @ConfigProperty(name = "arcclaw.tools.bulkhead.network.max-async", defaultValue = "8") int maxAsync,
            @ConfigProperty(name = "arcclaw.tools.bulkhead.network.max-queued", defaultValue = "20") int maxQueued) {
        return buildExecutor(ToolCategory.NETWORK, maxAsync, maxQueued);
    }

    @Produces
    @ApplicationScoped
    @Identifier("compute-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor computeToolExecutor(
            @ConfigProperty(name = "arcclaw.tools.bulkhead.compute.max-async", defaultValue = "4") int maxAsync,
            @ConfigProperty(name = "arcclaw.tools.bulkhead.compute.max-queued", defaultValue = "10") int maxQueued) {
        return buildExecutor(ToolCategory.COMPUTE, maxAsync, maxQueued);
    }

    @Produces
    @ApplicationScoped
    @Identifier("database-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor databaseToolExecutor(
            @ConfigProperty(name = "arcclaw.tools.bulkhead.database.max-async", defaultValue = "6") int maxAsync,
            @ConfigProperty(name = "arcclaw.tools.bulkhead.database.max-queued", defaultValue = "20") int maxQueued) {
        return buildExecutor(ToolCategory.DATABASE, maxAsync, maxQueued);
    }

    @Produces
    @ApplicationScoped
    @Identifier("internal-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor internalToolExecutor(
            @ConfigProperty(name = "arcclaw.tools.bulkhead.internal.max-async", defaultValue = "20") int maxAsync,
            @ConfigProperty(name = "arcclaw.tools.bulkhead.internal.max-queued", defaultValue = "100") int maxQueued) {
        return buildExecutor(ToolCategory.INTERNAL, maxAsync, maxQueued);
    }

    private ManagedExecutor buildExecutor(ToolCategory category, int maxAsync, int maxQueued) {
        ManagedExecutor executor = ManagedExecutor.builder()
                .maxAsync(maxAsync)
                .maxQueued(maxQueued)
                .build();
        if (meterRegistry != null) {
            meterRegistry.gauge("arcclaw.bulkhead.max-async",
                    List.of(Tag.of("category", category.name())),
                    executor, e -> maxAsync);
        }
        return executor;
    }
}
