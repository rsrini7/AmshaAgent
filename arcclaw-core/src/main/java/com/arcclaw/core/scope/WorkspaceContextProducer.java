package com.arcclaw.core.scope;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@Singleton
public class WorkspaceContextProducer {

    @Produces
    @WorkspaceScoped
    public WorkspaceContext produceWorkspaceContext() {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId == null) {
            throw new IllegalStateException("No active workspace session");
        }
        return new WorkspaceContext(sessionId);
    }
}
