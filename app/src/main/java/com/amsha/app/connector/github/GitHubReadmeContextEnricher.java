package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ContextEnricher;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/** Adds matching README excerpts only to the connecting owner in the active workspace. */
@ApplicationScoped
@Unremovable
public class GitHubReadmeContextEnricher implements ContextEnricher {

    private static final Logger log = Logger.getLogger(GitHubReadmeContextEnricher.class);

    private final GitHubReadmeConnectorService connectorService;
    private final IdentityStorePort identityStore;

    @Inject
    public GitHubReadmeContextEnricher(GitHubReadmeConnectorService connectorService, IdentityStorePort identityStore) {
        this.connectorService = connectorService;
        this.identityStore = identityStore;
    }

    @Override
    public String enrich(String sessionId, String userId, String goal, String basePrompt) {
        String base = basePrompt == null ? "" : basePrompt;
        if (userId == null || userId.isBlank() || goal == null || goal.isBlank()) {
            return base;
        }
        Optional<String> workspaceId = resolveWorkspaceId(userId);
        if (workspaceId.isEmpty()) {
            log.debugf("[GITHUB-ENRICHER] No workspace resolved for user=%s sessionId=%s", userId, sessionId);
            return base;
        }
        Optional<String> excerpt = connectorService.matchingMarkdown(userId, workspaceId.get(), goal);
        if (excerpt.isEmpty()) {
            log.debugf(
                    "[GITHUB-ENRICHER] No matching GitHub doc excerpt for user=%s, workspace=%s, goal=%s",
                    userId, workspaceId.get(), goal);
            return base;
        }
        log.infof(
                "[GITHUB-ENRICHER] Attached %d chars of GitHub documentation context for user=%s, workspace=%s",
                excerpt.get().length(), userId, workspaceId.get());
        return base
                + "\n\n## Connected GitHub documentation context\n"
                + "Repository documentation has been synchronized and retrieved for this request:\n"
                + excerpt.get()
                + "\n\nCRITICAL DIRECTIVE FOR REPOSITORY QUESTIONS:\n"
                + "When the user asks questions about this repository, its architecture, code structure, channels, memory, or components, answer DIRECTLY using the Connected GitHub documentation context above.\n"
                + "Do NOT delegate to Saraswati or other research agents, do NOT call web search or browser tools, and do NOT invoke external research tools unless the user explicitly asks for information outside this repository.\n";
    }

    private Optional<String> resolveWorkspaceId(String userId) {
        Optional<String> active = WorkspaceContext.activeWorkspaceId();
        if (active.isPresent()) {
            return active;
        }
        if (identityStore != null && userId != null && !userId.isBlank()) {
            List<WorkspaceMembershipDto> memberships = identityStore.listMembershipsByUserId(userId);
            if (memberships.size() == 1) {
                String workspaceId = memberships.getFirst().workspaceId();
                if (workspaceId != null && !workspaceId.isBlank()) {
                    return Optional.of(workspaceId);
                }
            }
        }
        return Optional.empty();
    }
}
