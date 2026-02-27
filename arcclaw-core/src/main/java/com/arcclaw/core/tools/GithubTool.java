package com.arcclaw.core.tools;

import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference tool implementation: GitHub CLI operations via sandboxed execution.
 * <p>
 * Supports 'repo' and 'gist' subcommands.
 */
@ApplicationScoped
public class GithubTool implements AgentTool {

    private static final org.jboss.logging.Logger log = org.jboss.logging.Logger.getLogger(GithubTool.class);

    private static final Set<String> ALLOWED_ACTIONS  = Set.of("repo", "gist");
    private static final Set<String> ALLOWED_COMMANDS = Set.of("view", "list");

    @Override
    public String name() {
        return "github";
    }

    @Override
    public String description() {
        return "Interact with GitHub using the gh CLI. " +
                "Parameters: " +
                "1. 'action': 'repo' or 'gist'. " +
                "2. 'command': 'view' or 'list'. " +
                "3. 'target': (Optional) The target owner or owner/repo path.";
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
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String target = (String) params.get("target");

        // ── ALLOWLIST GUARD (Java layer — second line of defence) ──────────
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                "Action '" + action + "' is not permitted. " +
                "Only 'repo' and 'gist' are supported."
            );
        }
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new IllegalArgumentException(
                "Command '" + command + "' is not permitted. " +
                "Only 'view' and 'list' are supported."
            );
        }
        // ── END ALLOWLIST GUARD ────────────────────────────────────────────

        // SMART GUARDRAILS: Teach the LLM how to use the CLI correctly
        if ("repo".equals(action)) {
            if ("view".equals(command) && (target == null || !target.contains("/"))) {
                throw new IllegalArgumentException(
                    "Invalid target '" + target + "'. To 'view' a repository, you MUST provide the full 'owner/repo' format."
                );
            }
            if ("list".equals(command) && target != null && target.contains("/")) {
                throw new IllegalArgumentException(
                    "Invalid target '" + target + "'. To 'list' repositories, provide ONLY the owner name."
                );
            }
        } else if ("gist".equals(action)) {
            if ("list".equals(command) && target != null && !target.isBlank()) {
                // gh gist list does not take a username. It lists for the auth user.
                // We drop the target to avoid "unknown command" errors.
                log.warnf("Dropping target '%s' for 'gh gist list' as it only supports listing for the authenticated user.", target);
                target = null; 
            }
            if ("view".equals(command) && (target == null || target.isBlank())) {
                throw new IllegalArgumentException(
                    "To 'view' a gist, you MUST provide the Gist ID or URL as the 'target'."
                );
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("gh");
        cmd.add(action);
        cmd.add(command);

        if (target != null && !target.isBlank()) {
            cmd.add(target);
        }
        
        return cmd;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        throw new UnsupportedOperationException("GithubTool requires sandbox execution");
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addEnumProperty("action",  List.of("repo", "gist"),
                "Action to perform")
            .addEnumProperty("command", List.of("view", "list"),
                "Command to run.")
            .addStringProperty("target",
                "For 'repo view', use 'owner/repo'. " +
                "For 'repo list', use 'owner'. " +
                "For 'gist view', use 'GistID'. " +
                "For 'gist list', OMIT this parameter (it lists your own gists).")
            .required("action", "command")
            .build();
    }
}