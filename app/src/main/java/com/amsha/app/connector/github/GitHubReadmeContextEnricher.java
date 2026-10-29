package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.agent.reasoning.ContextEnricher;
import com.amsha.core.scope.WorkspaceContext;

import io.quarkus.arc.Unremovable;

/** Adds matching README excerpts only to the connecting owner in the active workspace. */
@ApplicationScoped
@Unremovable
public class GitHubReadmeContextEnricher implements ContextEnricher {

    private final GitHubReadmeConnectorService connectorService;

    @Inject
    public GitHubReadmeContextEnricher(GitHubReadmeConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @Override
    public String enrich(String sessionId, String userId, String goal, String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        if (userId == null || userId.isBlank() || goal == null || goal.isBlank()) {
            return base;
        }
        Optional<String> workspaceId = WorkspaceContext.activeWorkspaceId();
        if (workspaceId.isEmpty()) {
            return base;
        }
        Optional<String> excerpt = connectorService.matchingMarkdown(userId, workspaceId.get(), goal);
        if (excerpt.isEmpty()) {
            return base;
        }
        return base + "\n\n## Connected GitHub README context\n" + excerpt.get() + "\n";
    }
}
