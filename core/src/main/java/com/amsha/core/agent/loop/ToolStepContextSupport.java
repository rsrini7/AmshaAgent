package com.amsha.core.agent.loop;

import java.util.Map;

/** Small context-value helpers used while interpreting a single tool step. */
final class ToolStepContextSupport {
    private ToolStepContextSupport() {}

    static String extractParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value instanceof String text ? text : (value == null ? "" : value.toString());
    }

    /**
     * Returns an empty string for an absent scheduling hint. This is the safe representation for
     * ScopedValue bindings and prevents {@code String.valueOf(null)} from becoming the literal
     * non-blank string {@code "null"}.
     */
    static String suggestedContextValue(AgentExecutionContext context, String key) {
        if (context == null || context.data() == null) {
            return "";
        }
        Object value = context.data().get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
