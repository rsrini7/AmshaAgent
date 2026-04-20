package com.arcclaw.app.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Named;

import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Produces dedicated ManagedExecutor pools for tool execution (§T5-2).
 * Separates internal tools from slow external tools to prevent starvation.
 */
@ApplicationScoped
public class ToolExecutorProducer {

    @Produces
    @ApplicationScoped
    @Named("internal-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor internalToolExecutor() {
        return ManagedExecutor.builder()
                .maxAsync(20)
                .build();
    }

    @Produces
    @ApplicationScoped
    @Named("external-tool-executor")
    @Typed(ManagedExecutor.class)
    public ManagedExecutor externalToolExecutor() {
        return ManagedExecutor.builder()
                .maxAsync(10)
                .build();
    }
}

