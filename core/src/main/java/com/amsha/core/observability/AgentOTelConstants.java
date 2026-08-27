package com.amsha.core.observability;

import io.opentelemetry.api.common.AttributeKey;

/**
 * OpenTelemetry semantic conventions for Amsha Agent.
 * <p>
 * Naming convention:
 * <ul>
 *   <li>Spans: {@code amsha.<domain>.<operation>} (dots separate segments)</li>
 *   <li>Attributes: {@code amsha.<domain>.<field>} (dots separate segments)</li>
 * </ul>
 */
public final class AgentOTelConstants {

    private AgentOTelConstants() {}

    // ── Span Names ──────────────────────────────────────────────────────
    public static final String SPAN_SESSION = "amsha.session";
    public static final String SPAN_AGENT_ITERATION = "amsha.agent.iteration";
    public static final String SPAN_TOOL_CALL = "amsha.tool.call";
    public static final String SPAN_TOOL_CONTAINER_EXEC = "amsha.tool.container_exec";
    public static final String SPAN_TOOL_GOVERNANCE = "amsha.tool.governance";
    public static final String SPAN_REFLEX_EXECUTE = "amsha.reflex.execute";
    public static final String SPAN_LLM_PROVIDER_CALL = "amsha.llm.provider_call";
    public static final String SPAN_VAULT_STORE = "amsha.vault.store";
    public static final String SPAN_VAULT_RETRIEVE = "amsha.vault.retrieve";
    public static final String SPAN_HITL_RESOLVE = "amsha.hitl.resolve";
    public static final String SPAN_DELEGATION_DISPATCH = "amsha.delegation.dispatch";
    public static final String SPAN_STRUCTURED_OUTPUT = "amsha.structured.output";
    public static final String SPAN_COMPACTION = "amsha.compaction.execute";
    public static final String SPAN_CRON_DISPATCH = "amsha.cron.dispatch";
    public static final String SPAN_CHECKPOINT_TRANSITION = "amsha.checkpoint.transition";

    // ── Attribute Keys ──────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_SESSION_ID = AttributeKey.stringKey("amsha.session.id");
    public static final AttributeKey<String> ATTR_USER_ID = AttributeKey.stringKey("amsha.user.id");
    public static final AttributeKey<String> ATTR_AGENT_ROLE = AttributeKey.stringKey("amsha.agent.role");
    public static final AttributeKey<String> ATTR_TOOL_NAME = AttributeKey.stringKey("amsha.tool.name");
    public static final AttributeKey<String> ATTR_TOOL_OPERATION = AttributeKey.stringKey("amsha.tool.operation");
    public static final AttributeKey<Long> ATTR_ITERATION = AttributeKey.longKey("amsha.iteration");
    public static final AttributeKey<String> ATTR_CHANNEL_TYPE = AttributeKey.stringKey("amsha.channel.type");

    // ── Vault ───────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_VAULT_KEY_NAME = AttributeKey.stringKey("amsha.vault.key_name");
    public static final AttributeKey<Boolean> ATTR_VAULT_FOUND = AttributeKey.booleanKey("amsha.vault.found");

    // ── Reflex ──────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_REFLEX_NAME = AttributeKey.stringKey("amsha.reflex.name");
    public static final AttributeKey<String> ATTR_REFLEX_EVENT = AttributeKey.stringKey("amsha.reflex.event_type");

    // ── LLM ─────────────────────────────────────────────────────────────
    public static final AttributeKey<Long> ATTR_LLM_INPUT_TOKENS = AttributeKey.longKey("amsha.llm.input_tokens");
    public static final AttributeKey<Long> ATTR_LLM_OUTPUT_TOKENS = AttributeKey.longKey("amsha.llm.output_tokens");
    public static final AttributeKey<Long> ATTR_LLM_MESSAGE_COUNT = AttributeKey.longKey("amsha.llm.message_count");
    public static final AttributeKey<Boolean> ATTR_LLM_WRITE_INTENT = AttributeKey.booleanKey("amsha.llm.write_intent");

    // ── Delegation ──────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_DELEGATION_TARGET = AttributeKey.stringKey("amsha.delegation.target");
    public static final AttributeKey<Long> ATTR_DELEGATION_DEPTH = AttributeKey.longKey("amsha.delegation.depth");
    public static final AttributeKey<Long> ATTR_DELEGATION_GOAL_LEN =
            AttributeKey.longKey("amsha.delegation.goal_length");

    // ── HITL ────────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_HITL_TASK_ID = AttributeKey.stringKey("amsha.hitl.task_id");
    public static final AttributeKey<String> ATTR_HITL_TOOL_NAME = AttributeKey.stringKey("amsha.hitl.tool_name");
    public static final AttributeKey<Boolean> ATTR_HITL_APPROVED = AttributeKey.booleanKey("amsha.hitl.approved");

    // ── Sandbox ─────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_CONTAINER_IMAGE = AttributeKey.stringKey("amsha.sandbox.image");
    public static final AttributeKey<String> ATTR_CONTAINER_NAME = AttributeKey.stringKey("amsha.sandbox.name");
    public static final AttributeKey<String> ATTR_SANDBOX_STRATEGY = AttributeKey.stringKey("amsha.sandbox.strategy");

    // ── OpenInference / Phoenix semantic conventions ─────────────────────
    public static final AttributeKey<String> ATTR_OI_SPAN_KIND = AttributeKey.stringKey("openinference.span.kind");
    public static final AttributeKey<String> ATTR_LLM_MODEL_NAME = AttributeKey.stringKey("llm.model_name");
    public static final AttributeKey<String> ATTR_LLM_INVOCATION_PARAMS =
            AttributeKey.stringKey("llm.invocation_parameters");
    public static final AttributeKey<String> ATTR_LLM_INPUT_MESSAGES = AttributeKey.stringKey("llm.input_messages");
    public static final AttributeKey<String> ATTR_LLM_OUTPUT_MESSAGES = AttributeKey.stringKey("llm.output_messages");
    public static final AttributeKey<String> ATTR_TOOL_CALL_NAME = AttributeKey.stringKey("llm.tool_call.name");
    public static final AttributeKey<String> ATTR_TOOL_CALL_ARGS = AttributeKey.stringKey("llm.tool_call.arguments");

    // ── OpenInference span kind values ─────────────────────────────────
    public static final String OI_KIND_AGENT = "AGENT";
    public static final String OI_KIND_CHAIN = "CHAIN";
    public static final String OI_KIND_TOOL = "TOOL";
    public static final String OI_KIND_LLM = "LLM";
    public static final String OI_KIND_RETRIEVER = "RETRIEVER";

    // ── Backward-compatible token attribute keys ────────────────────────
    public static final AttributeKey<Long> ATTR_INPUT_TOKENS = AttributeKey.longKey("llm.usage.prompt_tokens");
    public static final AttributeKey<Long> ATTR_OUTPUT_TOKENS = AttributeKey.longKey("llm.usage.completion_tokens");
    public static final AttributeKey<String> ATTR_ERROR_TYPE = AttributeKey.stringKey("error.type");

    /** Marks a manually instrumented provider call so the listener pilot cannot double-count it. */
    public static final ScopedValue<Boolean> MANUAL_LLM_INSTRUMENTATION = ScopedValue.newInstance();
}
