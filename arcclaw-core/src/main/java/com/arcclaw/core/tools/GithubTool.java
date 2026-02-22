package com.arcclaw.core.tools;

import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Reference tool implementation: GitHub CLI operations via sandboxed execution.
 * <p>
 * Executes {@code gh repo view <owner/repo>} inside the Podman sandbox.
 * The {@code GITHUB_TOKEN} is resolved from the vault and injected into the
 * container environment via an ephemeral env-file (never passed as a process
 * argument).
 * <p>
 * This tool requires {@link ToolOperation#NETWORK_OUTBOUND} autonomy — blocked
 * under {@code READ_ONLY} and {@code PARTIAL} levels.
 */
@ApplicationScoped
public class GithubTool implements AgentTool {

    private static final Logger log = Logger.getLogger(GithubTool.class);

    @Inject
    ProcessIsolationExecutor executor;

    @Inject
    WorkspaceContext workspaceContext;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @Override
    public String name() {
        return "github";
    }

    @Override
    public String description() {
        return "View GitHub repository information using the gh CLI. " +
                "Requires 'repo' parameter (e.g., 'owner/repo-name').";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;
    }

    /**
     * Executes {@code gh repo view <repo>} inside the sandbox.
     * <p>
     * The {@code GITHUB_TOKEN} secret is declared via {@code @RequiresSecret}
     * and resolved by the {@link ToolRegistry} before sandbox execution.
     *
     * @param params Must contain {@code "repo"} key (e.g.,
     *               {@code "owner/repo-name"}).
     * @return The tool response with the command output.
     */
    @Override
    @RequiresSecret(envVar = "GITHUB_TOKEN", vaultKey = "GITHUB_TOKEN")
    public ToolResponse execute(Map<String, Object> params) {
        var repo = (String) params.get("repo");
        if (repo == null || repo.isBlank()) {
            return ToolResponse.failure(1, "Missing required parameter: 'repo'");
        }

        var sessionId = workspaceContext.getSessionId();
        // Extract secrets declared on this method via @RequiresSecret
        var requiredSecrets = ToolRegistry.extractRequiredSecrets(GithubTool.class);
        var command = List.of("gh", "repo", "view", repo);

        // Dynamically route the tool to the user's isolated session folder
        java.nio.file.Path workspaceRoot = java.nio.file.Path.of(workspaceRootDir, workspaceContext.getSessionId());
        
        try {
            if (!java.nio.file.Files.exists(workspaceRoot)) {
                java.nio.file.Files.createDirectories(workspaceRoot);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create isolated workspace for session: " + workspaceContext.getSessionId(), e);
        }

        try {
            log.infof("Executing 'gh repo view %s' in sandbox for session %s", repo, sessionId);
            String output = executor.executeSandboxed(sessionId, workspaceRoot, command, requiredSecrets);
            return ToolResponse.success(output);
        } catch (SecurityException e) {
            return ToolResponse.failure(1, "Security violation: " + e.getMessage());
        } catch (Exception e) {
            log.errorf(e, "GitHub tool execution failed for repo '%s'", repo);
            return ToolResponse.failure(1, "Execution failed: " + e.getMessage());
        }
    }
}
