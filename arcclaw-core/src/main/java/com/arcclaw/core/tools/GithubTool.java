package com.arcclaw.core.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.security.VaultKey;
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
@ToolMetadata(
    capabilityId = "github",
    description = "Read-only access to GitHub repositories and resources",
    defaultRoles = {"architect", "coordinator", "researcher", "debugger", "synthesizer"}
)
public class GithubTool implements AgentTool {

    private static final Logger log = Logger.getLogger(GithubTool.class);

    @Inject
    @ConfigProperty(name = "arcclaw.tools.github.repos-per-page", defaultValue = "100")
    int reposPerPage;

    @ConfigProperty(name = "arcclaw.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Inject
    SecretVault secretVault;

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
                "1. 'action': ONLY 'repo' or 'gist' - no other values are valid. " +
                "2. 'command': ONLY 'view' or 'list' - NEVER use 'delete', 'create', 'remove', or any other value. " +
                "3. 'target': (Optional) The target owner or owner/repo path. " +
                "4. 'ref': (Optional) Branch, tag, or commit SHA for README fetch. OMIT if not needed.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;
    }

    @Override
    public boolean requiresSandbox() {
        // Sandbox (Podman) preferred for GH_TOKEN isolation. When Podman is offline and
        // ARCCLAW_SANDBOX_ALLOW_INSECURE_FALLBACK=true, ProcessIsolationExecutor falls
        // back to direct host execution via sandboxCommand(). Do NOT flip this to false
        // — that bypasses sandbox entirely and loses the fallback-on-unavailable behavior.
        return true;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
    }


    @Override
    @RequiresSecret(envVar = "GH_TOKEN", vaultKey = "ARCCLAW_TOOLS_GITHUB_TOKEN")
    public List<String> sandboxCommand(ToolExecutionContext ctx, Map<String, Object> params) {
        return buildGhCommand(params);
    }

    private List<String> buildGhCommand(Map<String, Object> params) {
        String action  = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String target  = (String) params.get("target");
        String ref     = (String) params.get("ref");

        // ── SMART NORMALIZATION: LLMs frequently confuse action vs command.
        // e.g. {"action":"view","command":"view"} → normalize to {"action":"repo","command":"view"}
        if (!ALLOWED_ACTIONS.contains(action) && ALLOWED_COMMANDS.contains(action)) {
            String normalizedCommand = action; // "view" or "list"
            if ("repo".equals(command) || "gist".equals(command)) {
                action = command; // swap: LLM put resource type in command
                command = normalizedCommand;
            } else {
                // Default: treat action="view" as action="repo", command="view"
                command = normalizedCommand;
                action = "repo";
            }
            log.infof("GithubTool: normalized LLM params → action=%s, command=%s", action, command);
        }

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

        // ── Case 3: repo view - use --json to get a compact summary instead of full README ──
        if ("repo".equals(action) && "view".equals(command)) {
            return List.of(
                    "gh", "repo", "view", target,
                    "--json", "name,description,url,repositoryTopics,primaryLanguage,visibility,stargazerCount,updatedAt"
            );
        }

        // ── Case 4: gist list - allow querying other users' gists via API ──
        if ("gist".equals(action) && "list".equals(command)) {
            if (target != null && !target.isBlank() && !target.equals("@me")) {
                // gh CLI can't list another user's gists - use API directly
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
                log.errorf("Failed to base64-decode README output for ref='%s': %s", ref, LogUtil.rootMessage(e));
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
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        try {
            List<String> command = buildGhCommand(params);

            // Resolve GH_TOKEN from vault for host-side execution
            String ghToken = resolveGhToken();

            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (ghToken != null) {
                pb.environment().put("GH_TOKEN", ghToken);
            }

            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolResponse.infraFailure("GitHub command timed out after " + timeoutSeconds + "s");
            }

            String result = output.trim();
            if (process.exitValue() != 0) {
                return ToolResponse.failure(1, "gh CLI failed (exit " + process.exitValue() + "): " + result);
            }

            return ToolResponse.success(postProcessOutput(
                    result.isBlank() ? "(no output)" : result, params));
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(1, e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            return ToolResponse.failure(1, msg != null ? msg : "GitHub tool execution failed");
        }
    }

    private String resolveGhToken() {
        try {
            VaultKey key = secretVault.retrieveSystemKey("ARCCLAW_TOOLS_GITHUB_TOKEN").orElse(null);
            if (key == null) return null;
            try (key) {
                return new String(key.get(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debugf("Could not resolve GH_TOKEN from vault: %s", LogUtil.rootMessage(e));
            return null;
        }
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
                                "NEVER use 'delete', 'create', 'remove', 'destroy', or any other value - " +
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
