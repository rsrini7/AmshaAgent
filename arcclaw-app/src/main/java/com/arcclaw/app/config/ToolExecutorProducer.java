package com.arcclaw.app.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;

import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Produces dedicated ManagedExecutor pools for tool execution (§T5-2).
 * Separates internal tools from slow external tools to prevent starvation.
 * <p>
 * Uses @Identifier instead of @Named to avoid implicit @Default qualifier
 * which causes ambiguous dependency errors in core modules (§P84 fix).
 */
@ApplicationScoped
public class ToolExecutorProducer {

    @Produces
    @ApplicationScoped
    @Identifier("internal-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor internalToolExecutor() {
        return ManagedExecutor.builder()
                .maxAsync(20)
                .build();
    }

    @Produces
    @ApplicationScoped
    @Identifier("external-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor externalToolExecutor() {
        return ManagedExecutor.builder()
                .maxAsync(10)
                .build();
    }
}
