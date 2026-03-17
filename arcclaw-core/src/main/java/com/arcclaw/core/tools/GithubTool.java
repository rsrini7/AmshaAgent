package com.arcclaw.core.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reference tool implementation: GitHub CLI operations via sandboxed execution.
 * <p>
 * Supports 'repo' and 'gist' subcommands.
 */
@ApplicationScoped
public class GithubTool implements AgentTool {

    private static final Logger log = Logger.getLogger(GithubTool.class);

    private static final Set<String> ALLOWED_ACTIONS  = Set.of("repo", "gist");
    private static final Set<String> ALLOWED_COMMANDS = Set.of("view", "list");

    public static final String NAME = ToolCapability.GITHUB.toolName();

    @Inject
    ProcessIsolationExecutor isolationExecutor;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolRegistry toolRegistry;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    @Override
    public String name() {
        return NAME;
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
        return false;
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

        String ref = (String) params.get("ref");
        if ("repo".equals(action) && "view".equals(command)
                && ref != null && !ref.isBlank()) {
            return List.of("gh", "api", "repos/" + target.trim() + "/readme?ref=" + ref.trim(), "--jq", ".content");
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
        List<String> command = sandboxCommand(params);
        var requiredSecrets = ToolRegistry.extractRequiredSecrets(this.getClass(), "sandboxCommand");
        String sessionId = WorkspaceContext.requireSessionId();
        Path workspaceRoot = Path.of(workspaceRootDir, sessionId);

        try {
            String output = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, command, requiredSecrets);

            // POST-PROCESSING: gh api readme returns base64 content
            String action = (String) params.getOrDefault("action", "repo");
            String subMsg = (String) params.getOrDefault("command", "view");
            String ref = (String) params.get("ref");
            boolean isApiReadmeCall = "repo".equals(action) && "view".equals(subMsg) && ref != null && !ref.isBlank();

            if (isApiReadmeCall && output != null && !output.isBlank()) {
                try {
                    output = new String(Base64.getMimeDecoder().decode(output.trim()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.errorf("Failed to decode base64 README output: %s", e.getMessage());
                }
            }

            return ToolResponse.success(output);
        } catch (Exception e) {
            log.errorf("GithubTool execution failed: %s", e.getMessage());
            return ToolResponse.failure(1, "GithubTool failed: " + e.getMessage());
        }
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addEnumProperty("action", List.of("repo", "gist"),
                "Action to perform")
            .addEnumProperty("command", List.of("view", "list"),
                "Command to run. Use 'view' to get description and README " +
                "(supply 'ref' param to target a specific branch, tag, or commit). " +
                "Use 'list' to enumerate items. " +
                "NEVER use desc, info, or any other value — ONLY view and list are valid.")
            .addStringProperty("target",
                "For repo view, use owner/repo. For repo list, use owner. " +
                "For gist view, use GistID. For gist list, OMIT this parameter.")
            .addStringProperty("ref",
                "Optional branch name, tag, or commit SHA. " +
                "Pass the value EXACTLY as-is, including hyphens and numbers. " +
                "Examples: Phase-63, main, v1.0.0, feature/my-branch. " +
                "Only valid when action=repo and command=view. Omit for list operations.")
            .required("action", "command")
            .build();
    }
}