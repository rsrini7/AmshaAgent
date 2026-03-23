package com.arcclaw.core.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

/**
 * Reference tool implementation: GitHub CLI operations via sandboxed execution.
 * Supports 'repo' and 'gist' subcommands, plus branch/tag README fetching via
 * the 'ref' parameter.
 */
@ApplicationScoped
public class GithubTool implements AgentTool {

    private static final Logger log = Logger.getLogger(GithubTool.class);

    private static final Set<String> ALLOWED_ACTIONS = Set.of("repo", "gist", "pr");
    private static final Set<String> ALLOWED_COMMANDS = Set.of("view", "list", "create", "status");

    public static final String NAME = ToolCapability.GITHUB.toolName();

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
                "3. 'target': (Optional) The target owner or owner/repo path. " +
                "4. 'ref': (Optional) Branch, tag, or commit SHA for README fetch.";
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
        String ref = (String) params.get("ref");

        // ── ALLOWLIST GUARD ────────────────────────────────────────────────
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "Action '" + action + "' is not permitted. Only 'repo' and 'gist' are supported.");
        }
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new IllegalArgumentException(
                    "Command '" + command + "' is not permitted. Only 'view' and 'list' are supported.");
        }
        // ── END ALLOWLIST GUARD ────────────────────────────────────────────

        // ── SMART GUARDRAILS ───────────────────────────────────────────────
        if ("repo".equals(action)) {
            if ("view".equals(command) && (target == null || !target.contains("/"))) {
                throw new IllegalArgumentException(
                        "Invalid target '" + target +
                                "'. To 'view' a repository, provide the full 'owner/repo' format.");
            }
            if ("list".equals(command) && target != null && target.contains("/")) {
                throw new IllegalArgumentException(
                        "Invalid target '" + target +
                                "'. To 'list' repositories, provide ONLY the owner name.");
            }
        } else if ("gist".equals(action)) {
            if ("list".equals(command) && target != null && !target.isBlank()) {
                log.warnf("Dropping target '%s' for 'gh gist list' — only lists authenticated user's gists.", target);
                target = null;
            }
            if ("view".equals(command) && (target == null || target.isBlank())) {
                throw new IllegalArgumentException(
                        "To 'view' a gist, provide the Gist ID or URL as 'target'.");
            }
        }
        // ── END SMART GUARDRAILS ───────────────────────────────────────────

        // ── REF / README BRANCH FETCH ──────────────────────────────────────
        // gh api returns base64-encoded content; postProcessOutput() decodes it.
        if ("repo".equals(action) && "view".equals(command)
                && ref != null && !ref.isBlank()) {
            return List.of(
                    "gh", "api",
                    "repos/" + target + "/readme?ref=" + ref.trim(),
                    "--jq", ".content");
        }
        // ── END REF / README BRANCH FETCH ─────────────────────────────────

        List<String> cmd = new ArrayList<>();
        cmd.add("gh");
        cmd.add(action);
        cmd.add(command);
        if (target != null && !target.isBlank()) {
            cmd.add(target);
        }
        return cmd;
    }

    /**
     * Post-processes raw sandbox output.
     * When a ref-based README call was made, gh api returns base64 content
     * that must be decoded before the agent sees it.
     * All other calls return the output unchanged.
     */
    @Override
    public String postProcessOutput(String rawOutput, Map<String, Object> params) {
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String ref = (String) params.get("ref");

        boolean isReadmeApiCall = "repo".equals(action)
                && "view".equals(command)
                && ref != null && !ref.isBlank();

        if (isReadmeApiCall && rawOutput != null && !rawOutput.isBlank()) {
            try {
                return new String(
                        Base64.getMimeDecoder().decode(rawOutput.trim()),
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.errorf("Failed to base64-decode README output for ref='%s': %s", ref, e.getMessage());
                // Return raw output rather than losing it entirely
                return rawOutput;
            }
        }
        return rawOutput;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");

        // Commands that modify state or are public-facing
        Set<String> unsafeCommands = Set.of("create", "delete", "edit", "comment", "merge", "close", "reopen");
        
        // GH PR operations are generally treated with higher sensitivity
        if ("pr".equals(action)) {
            return !Set.of("list", "view", "status").contains(command);
        }

        return unsafeCommands.contains(command);
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        throw new UnsupportedOperationException("GithubTool requires sandbox execution");
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addEnumProperty("action", List.of("repo", "gist"),
                        "Action to perform")
                .addEnumProperty("command", List.of("view", "list"),
                        "Command to run. Use 'view' to get description and README " +
                                "(supply 'ref' to target a specific branch, tag, or commit). " +
                                "Use 'list' to enumerate items. " +
                                "NEVER use 'desc', 'info', or any other value — ONLY 'view' and 'list' are valid.")
                .addStringProperty("target",
                        "For 'repo view', use 'owner/repo'. " +
                                "For 'repo list', use 'owner'. " +
                                "For 'gist view', use 'GistID'. " +
                                "For 'gist list', OMIT this parameter.")
                .addStringProperty("ref",
                        "Optional branch, tag, or commit SHA. Pass EXACTLY as-is including hyphens. " +
                                "Examples: Phase-63, main, v1.0.0, feature/my-branch. " +
                                "Only valid when action=repo and command=view. Omit for list operations.")
                .required("action", "command")
                .build();
    }
}
