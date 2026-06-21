package com.arcclaw.core.observability;

import io.opentelemetry.api.common.AttributeKey;

/**
 * OpenTelemetry semantic conventions for ArcClaw.
 * <p>
 * Naming convention:
 * <ul>
 *   <li>Spans: {@code arcclaw.<domain>.<operation>} (dots separate segments)</li>
 *   <li>Attributes: {@code arcclaw.<domain>.<field>} (dots separate segments)</li>
 * </ul>
 */
public final class AgentOTelConstants {

    private AgentOTelConstants() {}

    // ── Span Names ──────────────────────────────────────────────────────
    public static final String SPAN_SESSION = "arcclaw.session";
    public static final String SPAN_AGENT_ITERATION = "arcclaw.agent.iteration";
    public static final String SPAN_TOOL_CALL = "arcclaw.tool.call";
    public static final String SPAN_TOOL_CONTAINER_EXEC = "arcclaw.tool.container_exec";
    public static final String SPAN_TOOL_GOVERNANCE = "arcclaw.tool.governance";
    public static final String SPAN_REFLEX_EXECUTE = "arcclaw.reflex.execute";
    public static final String SPAN_LLM_PROVIDER_CALL = "arcclaw.llm.provider_call";
    public static final String SPAN_VAULT_STORE = "arcclaw.vault.store";
    public static final String SPAN_VAULT_RETRIEVE = "arcclaw.vault.retrieve";
    public static final String SPAN_HITL_RESOLVE = "arcclaw.hitl.resolve";
    public static final String SPAN_DELEGATION_DISPATCH = "arcclaw.delegation.dispatch";
    public static final String SPAN_STRUCTURED_OUTPUT = "arcclaw.structured.output";
    public static final String SPAN_COMPACTION = "arcclaw.compaction.execute";
    public static final String SPAN_CRON_DISPATCH = "arcclaw.cron.dispatch";
    public static final String SPAN_CHECKPOINT_TRANSITION = "arcclaw.checkpoint.transition";

    // ── Attribute Keys ──────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_SESSION_ID = AttributeKey.stringKey("arcclaw.session.id");
    public static final AttributeKey<String> ATTR_USER_ID = AttributeKey.stringKey("arcclaw.user.id");
    public static final AttributeKey<String> ATTR_AGENT_ROLE = AttributeKey.stringKey("arcclaw.agent.role");
    public static final AttributeKey<String> ATTR_TOOL_NAME = AttributeKey.stringKey("arcclaw.tool.name");
    public static final AttributeKey<String> ATTR_TOOL_OPERATION = AttributeKey.stringKey("arcclaw.tool.operation");
    public static final AttributeKey<Long> ATTR_ITERATION = AttributeKey.longKey("arcclaw.iteration");
    public static final AttributeKey<String> ATTR_CHANNEL_TYPE = AttributeKey.stringKey("arcclaw.channel.type");

    // ── Vault ───────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_VAULT_KEY_NAME = AttributeKey.stringKey("arcclaw.vault.key_name");
    public static final AttributeKey<Boolean> ATTR_VAULT_FOUND = AttributeKey.booleanKey("arcclaw.vault.found");

    // ── Reflex ──────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_REFLEX_NAME = AttributeKey.stringKey("arcclaw.reflex.name");
    public static final AttributeKey<String> ATTR_REFLEX_EVENT = AttributeKey.stringKey("arcclaw.reflex.event_type");

    // ── LLM ─────────────────────────────────────────────────────────────
    public static final AttributeKey<Long> ATTR_LLM_INPUT_TOKENS = AttributeKey.longKey("arcclaw.llm.input_tokens");
    public static final AttributeKey<Long> ATTR_LLM_OUTPUT_TOKENS = AttributeKey.longKey("arcclaw.llm.output_tokens");
    public static final AttributeKey<Long> ATTR_LLM_MESSAGE_COUNT = AttributeKey.longKey("arcclaw.llm.message_count");
    public static final AttributeKey<Boolean> ATTR_LLM_WRITE_INTENT =
            AttributeKey.booleanKey("arcclaw.llm.write_intent");

    // ── Delegation ──────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_DELEGATION_TARGET =
            AttributeKey.stringKey("arcclaw.delegation.target");
    public static final AttributeKey<Long> ATTR_DELEGATION_DEPTH = AttributeKey.longKey("arcclaw.delegation.depth");
    public static final AttributeKey<Long> ATTR_DELEGATION_GOAL_LEN =
            AttributeKey.longKey("arcclaw.delegation.goal_length");

    // ── HITL ────────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_HITL_TASK_ID = AttributeKey.stringKey("arcclaw.hitl.task_id");
    public static final AttributeKey<String> ATTR_HITL_TOOL_NAME = AttributeKey.stringKey("arcclaw.hitl.tool_name");
    public static final AttributeKey<Boolean> ATTR_HITL_APPROVED = AttributeKey.booleanKey("arcclaw.hitl.approved");

    // ── Sandbox ─────────────────────────────────────────────────────────
    public static final AttributeKey<String> ATTR_CONTAINER_IMAGE = AttributeKey.stringKey("arcclaw.sandbox.image");
    public static final AttributeKey<String> ATTR_CONTAINER_NAME = AttributeKey.stringKey("arcclaw.sandbox.name");
    public static final AttributeKey<String> ATTR_SANDBOX_STRATEGY = AttributeKey.stringKey("arcclaw.sandbox.strategy");

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
}
