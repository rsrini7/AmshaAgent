package com.arcclaw.core.agent.loop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

/**
 * Parses and extracts information from LLM exception messages and malformed generations.
 */
@ApplicationScoped
public class LlmExceptionRouter {

    private static final Logger log = Logger.getLogger(LlmExceptionRouter.class);
    private static final Pattern UNAVAILABLE_TOOL_PATTERN = Pattern.compile("attempted to call tool '([^']+)'");
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final ObjectMapper objectMapper;

    @Inject
    public LlmExceptionRouter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected LlmExceptionRouter() {
        this(null);
    }

    public String extractUnavailableToolName(String rawError) {
        if (rawError == null) return null;
        Matcher m = UNAVAILABLE_TOOL_PATTERN.matcher(rawError);
        return m.find() ? m.group(1) : null;
    }

    public String extractFailedGeneration(String rawError) {
        if (rawError == null) return null;
        try {
            int start = rawError.indexOf('{');
            if (start < 0) return null;
            JsonNode root = objectMapper.readTree(rawError.substring(start));
            JsonNode node = root.path("failed_generation");
            if (node.isMissingNode()) {
                node = root.path("failedgeneration");
            }
            if (node.isMissingNode() || !node.isTextual()) return null;
            String result = node.asText().strip();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            log.debugf("extractFailedGeneration parse failed: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    public String extractToolNameFromFailedGen(String failedGen) {
        if (failedGen == null) return null;
        Matcher m = TOOL_NAME_PATTERN.matcher(failedGen);
        return m.find() ? m.group(1) : null;
    }

    public String extractFinishAnswer(String failedGeneration) {
        if (failedGeneration == null || failedGeneration.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(failedGeneration);
            JsonNode answerNode = root.path("answer");
            if (answerNode.isMissingNode()) {
                answerNode = root.path("parameters").path("answer");
            }
            if (answerNode.isTextual()) {
                return answerNode.asText().strip();
            }
        } catch (Exception e) {
            log.debugf("extractFinishAnswer parse failed: %s", LogUtil.rootMessage(e));
        }
        return null;
    }

    public int extractRetryAfter(String msg, int defaultSeconds) {
        if (msg == null) return defaultSeconds;
        Matcher m = Pattern.compile("try again in ([0-9.]+)s").matcher(msg);
        if (m.find()) return (int) Math.ceil(Double.parseDouble(m.group(1)));
        return defaultSeconds;
    }
}
