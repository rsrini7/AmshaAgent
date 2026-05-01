package com.arcclaw.core.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.ToolOperation;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Contract for all ArcClaw agent tools.
 * <p>
 * Implementations are discovered via CDI {@code Instance<AgentTool>} in
 * {@link ToolRegistry}. Each tool declares:
 * <ul>
 * <li>{@link #name()} - unique identifier used for invocation lookup.</li>
 * <li>{@link #description()} - human-readable purpose (surfaced to LLM
 * agents).</li>
 * <li>{@link #requiredOperation()} - the {@link ToolOperation} category for
 * governance checks against the session's {@code AutonomyLevel}.</li>
 * </ul>
 * <p>
 * Tools requiring vault secrets must annotate {@link #execute(Map)} with
 * {@link RequiresSecret @RequiresSecret}.
 * The registry reads these annotations reflectively to build the secrets map
 * before delegating to the sandbox executor.
 */
public interface AgentTool {

    /**
     * Unique tool name used for registry lookup (e.g., {@code "github"}).
     */
    String name();

    /**
     * Human-readable description surfaced to LLM agents for tool selection.
     */
    String description();

    /**
     * The operation category for autonomy governance checks.
     * <p>
     * The {@link ToolRegistry} verifies
     * {@code AutonomyPolicy.isAllowed(level, requiredOperation())}
     * before calling {@link #execute(Map)}.
     */
    ToolOperation requiredOperation();

    /**
     * Whether this tool's execution should be wrapped by ProcessIsolationExecutor.
     * Defaults to {@code true} - opt-OUT is the exception, not the rule.
     * <p>
     * Set to {@code false} only for trusted in-memory tools that do not
     * require OS-level isolation (e.g., pure computation or local read-only
     * access).
     */
    default boolean requiresSandbox() {
        return true;
    }

    /**
     * The OS command this tool executes inside the sandbox container.
     * <p>
     * IMPORTANT: Place {@code @RequiresSecret} annotations on THIS method,
     * NOT on {@link #execute(Map)}. The ToolRegistry scans this method
     * reflectively to build the ephemeral env-file for secret injection.
     * <p>
     * Only called by the registry when {@link #requiresSandbox()} returns true.
     *
     * @param params Tool-specific parameters (same as execute).
     * @return The OS command tokens to run inside the sandbox.
     * @throws IllegalArgumentException if required params are missing.
     */
    default List<String> sandboxCommand(Map<String, Object> params) {
        throw new UnsupportedOperationException(
                name() + " declared requiresSandbox=true but did not implement sandboxCommand()");
    }

    /**
     * Execute the tool with the given parameters.
     * <p>
     * Implementations should delegate to {@code ProcessIsolationExecutor}
     * for sandboxed execution. Parameters are tool-specific (e.g.,
     * {@code "repo" → "owner/name"} for GitHub operations).
     *
     * @param params Tool-specific parameters.
     * @return The execution result.
     */
    ToolResponse execute(Map<String, Object> params);

    default JsonObjectSchema parametersSchema() {
        return null;
    }

    /**
     * Optional post-processing of raw sandbox output before it is returned to the
     * agent.
     * Default: identity (return output unchanged).
     * Override for tool-specific transformations (e.g. base64 decode).
     */
    default String postProcessOutput(String rawOutput, Map<String, Object> params) {
        return rawOutput;
    }

    /**
     * Whether this specific tool invocation requires manual approval regardless of the autonomy level.
     * This is used for high-risk operations like 'gh pr create' or 'rm -rf'.
     *
     * @param params Tool-specific parameters.
     * @return true if approval is required.
     */
    default boolean requiresManualApproval(Map<String, Object> params) {
        return false;
    }

    /**
     * Whether this tool is currently enabled.
     * Defaults to true. If false, the tool is filtered out during registry registration.
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Prepares the workspace on the host before the sandbox starts.
     * Override this to write files that the sandbox command needs.
     */
    default void prepareWorkspace(Path workspaceRoot, Map<String, Object> params) throws IOException {}

    /**
     * Whether the registry should strip explicit 'null' values from parameters.
     * Some LLMs send optional fields as 'null' instead of omitting them.
     * <p>
     * Default is true for safety/cleanliness. Set to false if the target API
     * differentiates between an omitted field and an explicit 'null' (e.g.
     * using null to explicitly clear a value).
     *
     * @return true to strip nulls, false to preserve them.
     */
    default boolean shouldStripNullParams() {
        return true;
    }

    /**
     * Returns the idempotency mode for this tool. §7.38
     * Defaults to NON_IDEMPOTENT for a "fail-closed" posture.
     */
    default IdempotencyMode idempotencyMode() {
        return IdempotencyMode.NON_IDEMPOTENT;
    }
}
