package com.arcclaw.core.tools;

import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import jakarta.enterprise.context.ApplicationScoped;

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

    @Override
    public boolean requiresSandbox() {
        return true;
    }

    @Override
    @RequiresSecret(envVar = "GH_TOKEN", vaultKey = "GITHUB_TOKEN")
    public List<String> sandboxCommand(Map<String, Object> params) {
        var repo = (String) params.get("repo");
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: 'repo'");
        }
        return List.of("gh", "repo", "view", repo);
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        throw new UnsupportedOperationException(
                "GithubTool execution is handled by ToolRegistry sandbox wrapping. " +
                        "This method should never be called directly.");
    }
}
