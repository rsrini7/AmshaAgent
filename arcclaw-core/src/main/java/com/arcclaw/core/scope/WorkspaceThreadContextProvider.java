package com.arcclaw.core.scope;

import org.eclipse.microprofile.context.spi.ThreadContextController;
import org.eclipse.microprofile.context.spi.ThreadContextProvider;
import org.eclipse.microprofile.context.spi.ThreadContextSnapshot;

import java.util.Map;

/**
 * MicroProfile {@link ThreadContextProvider} to propagate the {@link WorkspaceScoped} 
 * session context via {@link WorkspaceScopeHolder}'s propagation bridge.
 * <p>
 * This ensures that when a {@link io.smallrye.context.SmallRyeManagedExecutor} or
 * reactive event loop captures context, the workspace session ID is carried over.
 */
public class WorkspaceThreadContextProvider implements ThreadContextProvider {

    public static final String WORKSPACE_CONTEXT_TYPE = "Workspace";

    @Override
    public ThreadContextSnapshot currentContext(Map<String, String> props) {
        String sessionId = WorkspaceScopeHolder.getIfActive();
        return new WorkspaceThreadContextSnapshot(sessionId);
    }

    @Override
    public ThreadContextSnapshot clearedContext(Map<String, String> props) {
        return new WorkspaceThreadContextSnapshot(null);
    }

    @Override
    public String getThreadContextType() {
        return WORKSPACE_CONTEXT_TYPE;
    }

    private static class WorkspaceThreadContextSnapshot implements ThreadContextSnapshot {
        private final String sessionId;

        WorkspaceThreadContextSnapshot(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public ThreadContextController begin() {
            String previous = WorkspaceScopeHolder.getIfActive();
            WorkspaceScopeHolder.setBridge(sessionId);
            return () -> WorkspaceScopeHolder.setBridge(previous);
        }
    }
}
