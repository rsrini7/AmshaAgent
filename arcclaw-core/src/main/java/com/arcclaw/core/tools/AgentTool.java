package com.arcclaw.core.tools;

import com.arcclaw.core.security.ToolOperation;

import java.util.Map;

/**
 * Contract for all ArcClaw agent tools.
 * <p>
 * Implementations are discovered via CDI {@code Instance<AgentTool>} in
 * {@link ToolRegistry}. Each tool declares:
 * <ul>
 * <li>{@link #name()} — unique identifier used for invocation lookup.</li>
 * <li>{@link #description()} — human-readable purpose (surfaced to LLM
 * agents).</li>
 * <li>{@link #requiredOperation()} — the {@link ToolOperation} category for
 * governance checks against the session's {@code AutonomyLevel}.</li>
 * </ul>
 * <p>
 * Tools requiring vault secrets must annotate {@link #execute(Map)} with
 * {@link com.arcclaw.core.security.RequiresSecret @RequiresSecret}.
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
}
