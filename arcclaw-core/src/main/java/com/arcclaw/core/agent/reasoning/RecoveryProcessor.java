package com.arcclaw.core.agent.reasoning;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.util.TextPatternUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles extraction of partial answers or code from truncated or malformed LLM responses.
 */
@ApplicationScoped
public class RecoveryProcessor {

    private static final Logger log = Logger.getLogger(RecoveryProcessor.class);
    private static final String FAILED_GEN_KEY = "\"failed_generation\":\"";
    private static final Pattern FENCED_CODE_BLOCK_PATTERN = Pattern.compile("(?s)```([\\w+#-]*)\\R(.*?)```");

    private final ObjectMapper objectMapper;
    private final TextPatternUtils textPatternUtils;

    @Inject
    public RecoveryProcessor(ObjectMapper objectMapper,
                            TextPatternUtils textPatternUtils) {
        this.objectMapper = objectMapper;
        this.textPatternUtils = textPatternUtils;
    }

    protected RecoveryProcessor() {
        this(null, null);
    }

    public record CodeBlock(String language, String code) {}

    public boolean isProseGenerationFailure(String message) {
        if (message == null) return false;
        int idx = message.indexOf(FAILED_GEN_KEY);
        if (idx == -1) return false;
        int start = idx + FAILED_GEN_KEY.length();
        if (start >= message.length()) return false;
        return true;
    }

    public String extractProseFromFailedGeneration(String message) {
        if (message == null) return null;
        log.debugf("[RecoveryProcessor] Attempting to extract prose from failed generation");
        int idx = message.indexOf(FAILED_GEN_KEY);
        if (idx == -1) return null;
        int start = idx + FAILED_GEN_KEY.length();
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < message.length(); i++) {
            char c = message.charAt(i);
            if (escape) {
                if (c == 'n') sb.append('\n');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().strip();
        if (result.isBlank()) return null;
        
        if (result.startsWith("{") && result.endsWith("}")) {
            try {
                JsonNode node = objectMapper.readTree(result);
                JsonNode answerNode = node.get("answer");
                if (answerNode != null && !answerNode.isNull()) return answerNode.asText();
                JsonNode paramsNode = node.get("parameters");
                if (paramsNode != null && !paramsNode.isNull()) {
                    JsonNode pAnswerNode = paramsNode.get("answer");
                    if (pAnswerNode != null && !pAnswerNode.isNull()) return pAnswerNode.asText();
                }
            } catch (Exception e) {
                log.debugf("Non-fatal: %s", ExceptionUtils.getRootCauseMessage(e));
            }
            return null;
        }
        return result;
    }

    public String extractPartialCodeFromTruncatedJson(String json) {
        if (json == null) return null;
        int codeIdx = json.indexOf("\"code\"");
        if (codeIdx == -1) return null;
        int startQuote = json.indexOf("\"", json.indexOf(":", codeIdx) + 1);
        if (startQuote == -1) return null;
        String partial = json.substring(startQuote + 1).strip();
        log.infof("[RecoveryProcessor] Successfully extracted %d chars of partial code from truncated JSON", partial.length());
        return partial.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t");
    }

    public boolean containsEmbeddedSandboxToolCall(String prose) {
        if (prose == null) return false;
        String lower = prose.toLowerCase();
        return lower.contains("\"sandbox_exec\"") || lower.contains(ToolCapability.SANDBOX_EXEC.toolName())
                || (lower.contains("\"code\"") && lower.contains("\"language\""));
    }

    public boolean looksLikeTruncatedOrPartialCode(String prose) {
        if (prose == null || prose.isBlank()) return false;
        if (prose.contains("u003c") || prose.contains("u003e") || prose.contains("u0026")) return true;
        if (prose.contains("```")) return false;
        String lower = prose.toLowerCase();
        boolean hasCodeKeywords = lower.contains("public class") || lower.contains("public static void")
                || lower.contains("func main") || lower.contains("func ")
                || lower.contains("import java") || lower.contains("import \"fmt\"")
                || lower.contains("def ") || lower.contains("#!/bin/bash")
                || lower.contains("#!/usr/bin/env");
        boolean looksStartMidSentence = prose.length() < 200 && Character.isLowerCase(prose.charAt(0)) && !prose.trim().endsWith(".");
        return hasCodeKeywords || looksStartMidSentence;
    }

    public boolean proseIsUnexecutedCodePlan(String prose) {
        if (prose == null || prose.isBlank()) return false;
        if (prose.contains("```")) return true;
        String lower = prose.toLowerCase();
        return lower.contains("public class ") || lower.contains("func main") 
            || lower.contains("def ") || lower.contains("package main")
            || lower.contains("import \"fmt\"") || lower.contains("public static void");
    }

    public CodeBlock extractLatestCodeBlock(List<ReasoningStep> history) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ReasoningStep step = history.get(i);
            CodeBlock block = extractCodeBlockFromText(extractCandidateText(step));
            if (block != null) return block;
        }
        return null;
    }

    private String extractCandidateText(ReasoningStep step) {
        if (step == null) return null;
        if (step.actionInput() != null) {
            Object answer = step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
            if (answer instanceof String s && !s.isBlank()) return s;
        }
        if (step.thought() != null && !step.thought().isBlank()) return step.thought();
        return step.observation();
    }

    public CodeBlock extractCodeBlockFromText(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = FENCED_CODE_BLOCK_PATTERN.matcher(text);
        CodeBlock last = null;
        while (matcher.find()) {
            String rawSpecifier = matcher.group(1);
            String language = normalizeExecutionLanguage(rawSpecifier);
            String code = matcher.group(2) != null ? matcher.group(2).trim() : "";
            if (language == null && (rawSpecifier == null || rawSpecifier.isBlank()) && !code.isBlank()) {
                language = textPatternUtils.inferLanguage(code);
            }
            if (language != null && !code.isBlank()) {
                log.debugf("[RecoveryProcessor] Found code block: language=%s, length=%d", language, code.length());
                last = new CodeBlock(language, code);
            }
        }
        return last;
    }

    private String normalizeExecutionLanguage(String rawLanguage) {
        if (rawLanguage == null) return null;
        String language = rawLanguage.trim().toLowerCase();
        return switch (language) {
            case "python3" -> "python";
            case "sh" -> "bash";
            case "javascript", "js" -> "node";
            case "java", "python", "bash", "node", "go" -> language;
            default -> null;
        };
    }
}
