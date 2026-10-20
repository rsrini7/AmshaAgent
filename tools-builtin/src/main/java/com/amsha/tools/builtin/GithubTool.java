package com.amsha.tools.builtin;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.security.api.RequiresSecret;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.ToolOperation;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolNames;
import com.amsha.tool.api.ToolResponse;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

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
@Unremovable
@ToolMetadata(
        capabilityId = ToolNames.GITHUB,
        description = "Read-only access to GitHub repositories and resources",
        defaultRoles = {
            AgentRoleEnum.ARCHITECT_ID,
            AgentRoleEnum.COORDINATOR_ID,
            AgentRoleEnum.RESEARCHER_ID,
            AgentRoleEnum.DEBUGGER_ID,
            AgentRoleEnum.SYNTHESIZER_ID
        })
@SuppressWarnings("java:S1192")
public class GithubTool implements AgentTool {

    private static final Logger log = Logger.getLogger(GithubTool.class);

    @Inject
    @ConfigProperty(name = "amsha.tools.github.repos-per-page", defaultValue = "100")
    int reposPerPage;

    @ConfigProperty(name = "amsha.sandbox.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @Inject
    SecretVault secretVault;

    @Inject
    AuditService auditService;

    // ── SINGLE SOURCE OF TRUTH
    // Keep ALLOWED_ACTIONS, ALLOWED_COMMANDS, description(), and parametersSchema()
    // perfectly in sync. Any divergence will cause the LLM to hallucinate values.
    private static final Set<String> ALLOWED_ACTIONS = Set.of("repo", "gist");
    private static final Set<String> ALLOWED_COMMANDS = Set.of("view", "list");
    private static final Pattern GITHUB_USERNAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9](?:[a-zA-Z0-9]|-(?=[a-zA-Z0-9])){0,38}$");

    public static final String TOOL_NAME = ToolCapability.GITHUB.toolName();

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Interact with GitHub using the gh CLI. Read-only access only. " + "Parameters: "
                + "1. 'action': ONLY 'repo' or 'gist' - no other values are valid. "
                + "2. 'command': ONLY 'view' or 'list' - NEVER use 'delete', 'create', 'remove', or any other value. "
                + "3. 'target': (Optional) For 'repo view' the owner/repo, for 'repo list' the owner, "
                + "for 'gist view' the GistID, for 'gist list' the GitHub username (omit for your own gists). "
                + "4. 'ref': (Optional) Branch, tag, or commit SHA for README fetch. OMIT if not needed.";
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.NETWORK_OUTBOUND;
    }

    @Override
    public boolean requiresSandbox() {
        // Sandbox (Podman) preferred for GH_TOKEN isolation. When Podman is offline and
        // AMSHA_SANDBOX_ALLOW_INSECURE_FALLBACK=true, ProcessIsolationExecutor falls
        // back to direct host execution via sandboxCommand(). Do NOT flip this to false
        // — that bypasses sandbox entirely and loses the fallback-on-unavailable behavior.
        return true;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.IDEMPOTENT;
    }

    @Override
    @RequiresSecret(envVar = "GH_TOKEN", vaultKey = VaultConstants.TOOLS_GITHUB_TOKEN)
    public List<String> sandboxCommand(ToolExecutionContext ctx, Map<String, Object> params) {
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String target = (String) params.get("target");
        auditService.record(
                ctx.sessionId(),
                ctx.userId(),
                ctx.agentRole(),
                SecurityEventType.TOOL_EXECUTED,
                "github action=" + action + " cmd=" + command + " target=" + (target != null ? target : "none"));
        return buildGhCommand(params);
    }

    private List<String> buildGhCommand(Map<String, Object> params) {
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String target = (String) params.get("target");
        String ref = (String) params.get("ref");

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

        // SMART GUARDRAILS
        if ("repo".equals(action)) {
            if ("view".equals(command) && (target == null || !target.contains("/"))) {
                throw new IllegalArgumentException("Invalid target '" + target
                        + "'. To 'view' a repository, provide the full 'owner/repo' format.");
            }
            if ("list".equals(command) && target != null && target.contains("/")) {
                throw new IllegalArgumentException(
                        "Invalid target '" + target + "'. To 'list' repositories, provide ONLY the owner name.");
            }
        } else if ("gist".equals(action)) {
            if ("view".equals(command) && (target == null || target.isBlank())) {
                throw new IllegalArgumentException("To 'view' a gist, provide the Gist ID or URL as 'target'.");
            }
        }

        // REF / README BRANCH FETCH
        // gh api returns base64-encoded content; postProcessOutput() decodes it.
        if ("repo".equals(action) && "view".equals(command) && ref != null && !ref.isBlank()) {
            return List.of("gh", "api", "repos/" + target + "/readme?ref=" + ref.trim(), "--jq", ".content");
        }

        List<String> cmd = new ArrayList<>();

        // ── Use `gh api` for repo list with JQ sorting and truncation ──
        if ("repo".equals(action) && "list".equals(command)) {
            String owner = (target != null && !target.isBlank()) ? target : "@me";
            return List.of(
                    "gh",
                    "api",
                    "users/" + owner + "/repos?per_page=" + reposPerPage,
                    "--jq",
                    "[. | sort_by(.stargazers_count) | reverse | .[:10] | .[] | {name: .full_name, stars: .stargazers_count, description: .description, language: .language}]");
        }

        // ── Case 3: repo view - use --json to get a compact summary instead of full README ──
        if ("repo".equals(action) && "view".equals(command)) {
            return List.of(
                    "gh",
                    "repo",
                    "view",
                    target,
                    "--json",
                    "name,description,url,repositoryTopics,primaryLanguage,visibility,stargazerCount,updatedAt");
        }

        // ── Case 4: gist list - allow querying other users' gists via API ──
        if ("gist".equals(action) && "list".equals(command)) {
            if (target != null && !target.isBlank() && !target.equals("@me")) {
                String cleanTarget = target.trim();
                if (!GITHUB_USERNAME_PATTERN.matcher(cleanTarget).matches()) {
                    throw new IllegalArgumentException("Invalid GitHub username target: " + cleanTarget);
                }
                // gh CLI can't list another user's gists - use API directly
                return List.of(
                        "gh",
                        "api",
                        "users/" + cleanTarget + "/gists",
                        "--jq",
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
        String action = (String) params.getOrDefault("action", "repo");
        String command = (String) params.getOrDefault("command", "view");
        String ref = (String) params.get("ref");

        boolean isReadmeApiCall = "repo".equals(action) && "view".equals(command) && ref != null && !ref.isBlank();

        if (isReadmeApiCall && rawOutput != null && !rawOutput.isBlank()) {
            try {
                return new String(Base64.getMimeDecoder().decode(rawOutput.trim()), StandardCharsets.UTF_8);
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
        Process process = null;
        try {
            List<String> command = buildGhCommand(params);

            // Resolve GH_TOKEN from vault for host-side execution
            String ghToken = resolveGhToken();

            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (ghToken != null) {
                pb.environment().put("GH_TOKEN", ghToken);
            }

            process = pb.start();
            ProcessOutput processOutput = waitForProcess(process);
            String output = processOutput.output();
            boolean finished = processOutput.finished();

            if (!finished || Thread.currentThread().isInterrupted()) {
                terminateProcess(process, null);
                return ToolResponse.infraFailure(
                        "GitHub command timed out or interrupted after " + timeoutSeconds + "s");
            }

            String result = output.trim();
            if (process.exitValue() != 0) {
                return ToolResponse.failure(1, "gh CLI failed (exit " + process.exitValue() + "): " + result);
            }

            return ToolResponse.success(postProcessOutput(result.isBlank() ? "(no output)" : result, params));
        } catch (InterruptedException e) {
            terminateProcess(process, null);
            Thread.currentThread().interrupt();
            return ToolResponse.failure(499, "GitHub command interrupted: " + LogUtil.rootMessage(e));
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(1, e.getMessage());
        } catch (Exception e) {
            terminateProcess(process, null);
            String msg = e.getMessage();
            return ToolResponse.failure(1, msg != null ? msg : "GitHub tool execution failed");
        } finally {
            terminateProcess(process, null);
        }
    }

    private ProcessOutput waitForProcess(Process process) throws InterruptedException {
        AtomicReference<String> output = new AtomicReference<>("");
        Thread reader = Thread.startVirtualThread(() -> {
            try {
                output.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException _) {
                // The process is being terminated or its output stream was closed.
            }
        });

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished || Thread.currentThread().isInterrupted()) {
                terminateProcess(process, reader);
                return new ProcessOutput(false, output.get());
            }

            reader.join(1_000);
            if (reader.isAlive()) {
                terminateProcess(process, reader);
                return new ProcessOutput(false, output.get());
            }
            return new ProcessOutput(true, output.get());
        } catch (InterruptedException e) {
            terminateProcess(process, reader);
            throw e;
        }
    }

    private void closeOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException _) {
            // Best effort while terminating the child process.
        }
    }

    private void terminateProcess(Process process, Thread reader) {
        if (process == null) {
            return;
        }
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        closeOutput(process);
        if (reader != null && reader.isAlive()) {
            try {
                reader.join(1_000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ProcessOutput(boolean finished, String output) {}

    private String resolveGhToken() {
        try {
            VaultKey key = secretVault
                    .retrieveSystemKey(VaultConstants.TOOLS_GITHUB_TOKEN)
                    .orElse(null);
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
                .addEnumProperty(
                        "action", List.of("repo", "gist"), "Action to perform. ONLY 'repo' or 'gist' are valid.")
                .addEnumProperty(
                        "command",
                        List.of("view", "list"),
                        "Command to run. Use 'view' to get description and README "
                                + "(supply 'ref' to target a specific branch, tag, or commit). "
                                + "Use 'list' to enumerate items. "
                                + "NEVER use 'delete', 'create', 'remove', 'destroy', or any other value - "
                                + "ONLY 'view' and 'list' are valid.")
                .addStringProperty(
                        "target",
                        "For 'repo view', use 'owner/repo'. " + "For 'repo list', use 'owner'. "
                                + "For 'gist view', use 'GistID'. "
                                + "For 'gist list', use the GitHub username, or omit for the authenticated user.")
                .addStringProperty(
                        "ref",
                        "Optional branch, tag, or commit SHA. Pass EXACTLY as-is including hyphens. "
                                + "Examples: main, v0.1.0, feature/my-branch. "
                                + "Only valid when action=repo and command=view. "
                                + "CRITICAL: If not needed, you MUST OMIT the 'ref' key entirely. NEVER pass null.")
                .required("action", "command")
                .build();
    }
}
