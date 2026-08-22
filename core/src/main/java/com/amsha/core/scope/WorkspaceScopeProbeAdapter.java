package com.amsha.core.scope;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.persistence.api.PersistenceScopeProbe;

/**
 * Core-side binding of {@link PersistenceScopeProbe}: lets persistence implementations
 * observe workspace-scope state without importing {@link WorkspaceScopeHolder} or any
 * other core type (AGENTS.md §5).
 */
@ApplicationScoped
public class WorkspaceScopeProbeAdapter implements PersistenceScopeProbe {

    @Override
    public boolean workspaceScopeActive() {
        return WorkspaceScopeHolder.isActive();
    }
}
