package com.amsha.core.scope;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enterprise-grade factory for creating and wrapping {@link ExecutorService}
 * instances that are aware of the {@link WorkspaceScoped} session context.
 * <p>
 * Ensures that developers do not accidentally lose the workspace context when
 * offloading tasks to virtual threads.
 */
public final class WorkspaceExecutors {

    private WorkspaceExecutors() {
        // factory class
    }

    /**
     * Creates a new virtual thread per task executor that is aware of the
     * workspace context.
     *
     * @return a context-aware virtual thread executor
     */
    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        return new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Wraps an existing {@link ExecutorService} to make it aware of the
     * workspace context.
     *
     * @param delegate the executor to wrap
     * @return a context-aware executor
     */
    public static ExecutorService wrap(ExecutorService delegate) {
        if (delegate instanceof ContextAwareExecutorService) {
            return delegate;
        }
        return new ContextAwareExecutorService(delegate);
    }
}
