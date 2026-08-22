package com.amsha.persistence.api;

/**
 * Minimal probe port for workspace-scope state, consumed by persistence implementations
 * that must react to scoped execution without importing core scope machinery
 * (AGENTS.md §5: persistence impls depend on persistence-api only).
 */
public interface PersistenceScopeProbe {

    /**
     * @return true when the current thread executes inside an active workspace scope.
     */
    boolean workspaceScopeActive();
}
