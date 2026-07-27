package com.amsha.core.scope;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

/**
 * The identity workspace explicitly selected for the current HTTP request.
 * This is intentionally independent from the agent/chat workspace session held by
 * {@link WorkspaceScopeHolder}; authorization is established by the entry-point filter.
 */
@RequestScoped
public class ActiveWorkspaceContext {

    private String workspaceId;

    public Optional<String> workspaceId() {
        return Optional.ofNullable(workspaceId);
    }

    public String requireWorkspaceId() {
        if (workspaceId == null) {
            throw new IllegalStateException("No active identity workspace is selected");
        }
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        this.workspaceId = workspaceId;
    }
}
