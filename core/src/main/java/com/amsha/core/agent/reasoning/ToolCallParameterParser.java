package com.amsha.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Parses and repairs tool-call parameter JSON.
 * Extracted from {@link ReasoningStepParser} so that parallel-tool collapse logic can reuse it
 * without duplicating the deserialization and sandbox recovery code.
 */
@ApplicationScoped
public class ToolCallParameterParser {

    private static final Logger log = Logger.getLogger(ToolCallParameterParser.class);

    private static final Pattern LANGUAGE_FIELD_PATTERN = Pattern.compile("\"language\"\\s*:\\s*\"([^\"]+)\"");

    private final ObjectMapper objectMapper;
    private final TextPatternUtils textPatternUtils;

    @Inject
    public ToolCallParameterParser(ObjectMapper objectMapper, TextPatternUtils textPatternUtils) {
        this.objectMapper = objectMapper;
        this.textPatternUtils = textPatternUtils;
    }

    /**
     * Parses the JSON arguments of a tool call, applying repair heuristics for malformed LLM output.
     * Returns a map containing {@code __error} when the JSON cannot be repaired.
     */
    public Map<String, Object> deserializeParams(String sessionId, String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();

        // Phase 1: Try direct parse
        try {
            return doDeserialize(argsJson);
        } catch (Exception e) {
            log.warnf(
                    "Initial JSON parse failed for session %s: %s. Attempting repair.",
                    sessionId, LogUtil.rootMessage(e));

            // Phase 2: Repair and retry
            try {
                String repaired = ReasoningStepParserUtils.sanitizeJson(argsJson);
                return doDeserialize(repaired);
            } catch (Exception e2) {
                if (ToolCapability.SANDBOX_EXEC.toolName().equals(toolName)) {
                    Map<String, Object> recovered = recoverSandboxExecParams(argsJson);
                    if (!recovered.isEmpty()) {
                        log.warnf("Recovered malformed sandbox_exec parameters for session %s", sessionId);
                        return recovered;
                    }
                }
                log.errorf("JSON repair failed for session %s: %s", sessionId, LogUtil.rootMessage(e2));
                return Map.of("__error", e.getMessage(), "parameters", argsJson);
            }
        }
    }

    /**
     * Recovers the {@code code} value from a sandbox_exec arguments JSON for display purposes.
     */
    public String recoverCodeForDisplay(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        if (objectMapper != null) {
            try {
                Map<String, Object> parsed = doDeserialize(argsJson);
                Object code = parsed.get("code");
                if (code != null && !code.toString().isBlank()) {
                    return code.toString();
                }
            } catch (Exception _) {
                // Fall through to malformed sandbox_exec recovery below.
            }
        }
        Map<String, Object> recovered = recoverSandboxExecParams(argsJson);
        return (String) recovered.get("code");
    }

    private Map<String, Object> doDeserialize(String json) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        if (parsed != null) {
            ReasoningStepParserUtils.coerceBooleans(parsed);
        }
        return parsed != null ? parsed : Map.of();
    }

    private Map<String, Object> recoverSandboxExecParams(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();

        int codeKey = argsJson.indexOf("\"code\"");
        if (codeKey < 0) return Map.of();

        int codeStart = argsJson.indexOf('"', argsJson.indexOf(':', codeKey) + 1);
        if (codeStart < 0) return Map.of();

        String rawCode;
        String language = null;

        int languageKey = argsJson.lastIndexOf("\"language\"");
        if (languageKey >= 0 && languageKey > codeStart) {
            int codeEnd = argsJson.lastIndexOf("\",", languageKey);
            if (codeEnd >= 0 && codeEnd > codeStart) {
                rawCode = argsJson.substring(codeStart + 1, codeEnd);
                Matcher matcher = LANGUAGE_FIELD_PATTERN.matcher(argsJson);
                if (matcher.find()) {
                    language = matcher.group(1);
                }
            } else {
                // language field exists but code field is truncated or messed up
                rawCode = argsJson.substring(codeStart + 1);
            }
        } else {
            // No language field - assume truncated
            rawCode = argsJson.substring(codeStart + 1);
        }

        String code = rawCode.replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");

        // Clean up trailing garbage from truncation (quotes and commas only)
        // DO NOT strip '}' as it might be a valid block end.
        if (code.endsWith("\"") || code.endsWith(",")) {
            code = code.substring(0, code.length() - 1);
        }

        if (language == null || language.isBlank()) {
            language = textPatternUtils.inferLanguage(code);
        }

        if (code.isBlank() || language == null || language.isBlank()) return Map.of();
        return Map.of("code", code, "language", language);
    }
}
