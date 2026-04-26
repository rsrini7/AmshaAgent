package com.arcclaw.core.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reference tool implementation: GitHub CLI operations via sandboxed execution.
 * Supports 'repo' and 'gist' subcommands, plus branch/tag README fetching via
 * the 'ref' parameter.
 *
 * <p>Security model:
 * <ul>
 *   <li>ALLOWED_ACTIONS and ALLOWED_COMMANDS are the single source of truth for
 *       what the LLM schema advertises and what the runtime guard enforces.
 *       They must always be in sync.</li>
 *   <li>Destructive commands (delete, destroy, remove, etc.) are intentionally
 *       absent from both sets and must never be added without a formal review.</li>
 * </ul>
 */
@ApplicationScoped
public class GithubTool implements AgentTool {

    private static final Logger log = Logger.getLogger(GithubTool.class);

    @Inject
    @ConfigProperty(name = "arcclaw.tools.github.repos-per-page", defaultValue = "100")
    int reposPerPage;

    // ── SINGLE SOURCE OF TRUTH 
    // Keep ALLOWED_ACTIONS, ALLOWED_COMMANDS, description(), and parametersSchema()
    // perfectly in sync. Any divergence will cause the LLM to hallucinate values.
    private static final Set<String> ALLOWED_ACTIONS  = Set.of("repo", "gist");
    private static final Set<String> ALLOWED_COMMANDS = Set.of("view", "list");

    public static final String NAME = ToolCapability.GITHUB.toolName();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Interact with GitHub using the gh CLI. Read-only access only. " +
                "Parameters: " +
                "1. 'action': ONLY 'repo' or 'gist' — no other values are valid. " +
                "2. 'command': ONLY 'view' or 'list' — NEVER use 'delete', 'create', 'remove', or any other value. " +
                "3. 'target': (Optional) The target owner or owner/repo path. " +
                "4. 'ref': (Optional) Branch, tag, or commit SHA for README fetch. OMIT if not needed.";
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
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
    }


    @Override
    @RequiresSecret(envVar = "GH_TOKEN", vaultKey = "ARCCLAW_TOOLS_GITHUB_TOKEN")
    public List<String> sandboxCommand(Map<String, Object> params) {
        String action  = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String target  = (String) params.get("target");
        String ref     = (String) params.get("ref");

        // ── ALLOWLIST GUARD 
        // This is the hard runtime enforcement. Must always match ALLOWED_ACTIONS
        // and ALLOWED_COMMANDS above and the schema advertised to the LLM.
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "Action '" + action + "' is not permitted. Only 'repo' and 'gist' are supported.");
        }
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new IllegalArgumentException(
                    "Command '" + command + "' is not permitted. Only 'view' and 'list' are supported.");
        }

        //SMART GUARDRAILS 
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
            if ("view".equals(command) && (target == null || target.isBlank())) {
                throw new IllegalArgumentException(
                        "To 'view' a gist, provide the Gist ID or URL as 'target'.");
            }
        }

        // REF / README BRANCH FETCH 
        // gh api returns base64-encoded content; postProcessOutput() decodes it.
        if ("repo".equals(action) && "view".equals(command)
                && ref != null && !ref.isBlank()) {
            return List.of(
                    "gh", "api",
                    "repos/" + target + "/readme?ref=" + ref.trim(),
                    "--jq", ".content");
        }

        List<String> cmd = new ArrayList<>();

        // ── Use `gh api` for repo list with JQ sorting and truncation ──
        if ("repo".equals(action) && "list".equals(command)) {
            String owner = (target != null && !target.isBlank()) ? target : "@me";
            return List.of(
                    "gh", "api",
                    "users/" + owner + "/repos?per_page=" + reposPerPage,
                    "--jq",
                    "[. | sort_by(.stargazers_count) | reverse | .[:10] | .[] | {name: .full_name, stars: .stargazers_count, description: .description, language: .language}]"
            );
        }

        // ── Case 3: repo view — use --json to get a compact summary instead of full README ──
        if ("repo".equals(action) && "view".equals(command)) {
            return List.of(
                    "gh", "repo", "view", target,
                    "--json", "name,description,url,repositoryTopics,primaryLanguage,visibility,stargazerCount,updatedAt"
            );
        }

        // ── Case 4: gist list — allow querying other users' gists via API ──
        if ("gist".equals(action) && "list".equals(command)) {
            if (target != null && !target.isBlank() && !target.equals("@me")) {
                // gh CLI can't list another user's gists — use API directly
                return List.of("gh", "api", "users/" + target + "/gists", "--jq",
                    "[.[:5] | .[] | {id, description, public, created_at, files: (.files | keys)}]");
            }
            return List.of("gh", "gist", "list", "--limit", "5"); // authenticated user
        }

        // Case 5: gist view, etc. use gh CLI directly
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
        String action  = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String ref     = (String) params.get("ref");

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
                return rawOutput;
            }
        }
        return rawOutput;
    }

    @Override
    public boolean requiresManualApproval(Map<String, Object> params) {
        // Read-only commands (view, list) return false here.
        // Approval is still enforced by the session's autonomy level (SUPERVISED/AUTONOMOUS).
        // Destructive commands are blocked at the schema + allowlist level and never reach here.
        return false;
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        throw new UnsupportedOperationException("GithubTool requires sandbox execution");
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addEnumProperty("action", List.of("repo", "gist"),
                        "Action to perform. ONLY 'repo' or 'gist' are valid.")
                .addEnumProperty("command", List.of("view", "list"),
                        "Command to run. Use 'view' to get description and README " +
                                "(supply 'ref' to target a specific branch, tag, or commit). " +
                                "Use 'list' to enumerate items. " +
                                "NEVER use 'delete', 'create', 'remove', 'destroy', or any other value — " +
                                "ONLY 'view' and 'list' are valid.")
                .addStringProperty("target",
                        "For 'repo view', use 'owner/repo'. " +
                                "For 'repo list', use 'owner'. " +
                                "For 'gist view', use 'GistID'. " +
                                "For 'gist list', OMIT this parameter.")
                .addStringProperty("ref",
                        "Optional branch, tag, or commit SHA. Pass EXACTLY as-is including hyphens. " +
                        "Examples: Phase-63, main, v1.0.0, feature/my-branch. " +
                        "Only valid when action=repo and command=view. " +
                        "CRITICAL: If not needed, you MUST OMIT the 'ref' key entirely. NEVER pass null.")
                .required("action", "command")
                .build();
    }
}
