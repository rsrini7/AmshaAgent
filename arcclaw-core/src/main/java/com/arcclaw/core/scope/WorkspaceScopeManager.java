package com.arcclaw.core.scope;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI Bean wrapper for {@link WorkspaceScopeHolder} to ensure that tests
 * interact
 * with the ClassLoader-correct version of the holder when running in
 * specialized
 * environments like @QuarkusTest.
 */
@ApplicationScoped
public class WorkspaceScopeManager {

    public void setSessionId(String sessionId) {
        WorkspaceScopeHolder.set(sessionId);
    }

    public String getSessionId() {
        return WorkspaceScopeHolder.get();
    }

    public void clear() {
        WorkspaceScopeHolder.clear();
    }
}
