package com.amsha.core.agent.reasoning;

/**
 * Shared JSON field name constants for tool call parsing.
 * <p>
 * These keys are used across multiple files to parse tool call JSON from LLM responses.
 * Centralizing them prevents drift between parsing paths (LeakedToolCallParser,
 * ReasoningStepDeserializer, ChatHistoryAssembler, NvidiaMessageSanitizer, etc.).
 */
public final class ToolCallJsonKeys {

    private ToolCallJsonKeys() {}

    /** The tool/function name field. */
    public static final String NAME = "name";

    /** Alternative tool name field (OpenAI format). */
    public static final String TOOL_NAME = "tool_name";

    /** Alternative tool name field (some providers). */
    public static final String TOOL = "tool";

    /** The action/command field (also used as tool name in some formats). */
    public static final String ACTION = "action";

    /** The function name field (OpenAI function calling format). */
    public static final String FUNCTION = "function";

    /** The parameters field (some LLM providers use this). */
    public static final String PARAMETERS = "parameters";

    /** The arguments field (OpenAI and others use this). */
    public static final String ARGUMENTS = "arguments";

    /** The answer field in FINISH steps. */
    public static final String ANSWER = "answer";

    /** The batch field for parallel tool calls. */
    public static final String BATCH = "batch";

    /** Empty JSON object string. */
    public static final String EMPTY_JSON = "{}";
}
