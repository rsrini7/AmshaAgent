package com.arcclaw.core.agent.reasoning;

import java.util.List;
import java.util.Map;

/**
 * Pure-static utilities extracted from {@link ReasoningStepParser} to keep it under the
 */
final class ReasoningStepParserUtils {

    private ReasoningStepParserUtils() {}

    /**
     * Strips model-artifact suffixes like {@code <|channel|>commentary} from tool names.
     * Some free models (e.g. OpenRouter) append these to function-call names.
     */
    static String sanitizeToolName(String name) {
        if (name == null) return null;
        // Strip <|channel|> commentary suffix
        int idx = name.indexOf("<|");
        if (idx > 0) name = name.substring(0, idx);
        // Strip trailing colon (e.g., "brahma:" → "brahma")
        if (name.endsWith(":")) {
            name = name.substring(0, name.length() - 1);
        }
        // Strip agent: prefix (e.g., "brahma:garuda" → "garuda")
        int colonIdx = name.indexOf(':');
        if (colonIdx > 0 && colonIdx < name.length() - 1) {
            String afterColon = name.substring(colonIdx + 1);
            // Tool names contain underscores; agent names don't
            if (afterColon.contains("_")) {
                name = afterColon;
            }
        }
        return name;
    }

    /**
     * Attempts to repair common LLM tool-calling JSON errors:
     * 1. Unescaped newlines within string values
     * 2. Unescaped double-quotes within what looks like a code field
     */
    static String sanitizeJson(String json) {
        if (json == null) return null;

        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        char prev = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            boolean isUnescapedQuote = (c == '"' && prev != '\\');
            if (isUnescapedQuote && inString) {
                char next = i + 1 < json.length() ? json.charAt(i + 1) : 0;
                if (next != ','
                        && next != '}'
                        && next != ']'
                        && next != ':'
                        && !Character.isWhitespace(next)
                        && next != 0) {
                    sb.append('\\');
                    sb.append(c);
                    prev = c;
                    continue;
                }
            }

            if (c == '"' && prev != '\\') {
                inString = !inString;
            }

            if (inString && (c == '\n' || c == '\r')) {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
            prev = c;
        }

        return sb.toString();
    }

    /**
     * Recursively coerces string "true"/"false" values to actual booleans in parsed JSON maps.
     * LLMs frequently emit boolean-like values as strings.
     */
    @SuppressWarnings("unchecked")
    static void coerceBooleans(Map<String, Object> map) {
        for (var entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                if ("true".equalsIgnoreCase(s)) {
                    entry.setValue(true);
                } else if ("false".equalsIgnoreCase(s)) {
                    entry.setValue(false);
                }
            } else if (value instanceof Map) {
                coerceBooleans((Map<String, Object>) value);
            } else if (value instanceof List list) {
                coerceBooleans(list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void coerceBooleans(List<Object> list) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String s) {
                if ("true".equalsIgnoreCase(s)) {
                    list.set(i, true);
                } else if ("false".equalsIgnoreCase(s)) {
                    list.set(i, false);
                }
            } else if (item instanceof Map) {
                coerceBooleans((Map<String, Object>) item);
            } else if (item instanceof List innerList) {
                coerceBooleans(innerList);
            }
        }
    }
}
