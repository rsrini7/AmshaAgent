package com.arcclaw.core.structured;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import org.jboss.logging.Logger;

/**
 * Entry point for structured output calls.
 */
@ApplicationScoped
public class StructuredOutputEngine {
    private static final Logger log = Logger.getLogger(StructuredOutputEngine.class);

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    StructuredRetryLoop retryLoop;

    @Inject
    PermissiveJsonExtractor extractor;

    /**
     * Invokes the LLM to produce a structured result of type T.
     */
    public <T> T invoke(String sessionId, String agentRole, String combinedContext, String functionName, Class<T> targetType) {
        String schema = loadSchema(functionName);
        String schemaPrompt = String.format("""
            CONTEXT TO PROCESS:
            %s

            STRICT OUTPUT FORMAT:
            You must return your response as a JSON object matching the following schema:
            %s
            
            Return a JSON INSTANCE with actual data values.
            Do NOT wrap your response in schema keys like "type" or "properties".
            Do NOT return the schema itself - return the DATA that matches the schema.
            Do not include any thought process, markdown text, or explanations outside the JSON block.
            Return ONLY the JSON.
            """, combinedContext, schema);

        return retryLoop.execute(sessionId, agentRole, "structured_output:" + functionName, () -> {
            String rawResponse = reasoningEngine.chatForStructuredOutput(sessionId, agentRole, schemaPrompt);
            return extractor.extract(rawResponse, targetType);
        });
    }

    private String loadSchema(String functionName) {
        String path = "arcclaw/schemas/" + functionName + ".json";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.warnf("Schema resource not found at %s. Falling back to generic {} object.", path);
                return "{}";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.errorf("Error loading schema %s: %s", path, LogUtil.rootMessage(e));
            return "{}";
        }
    }

    /**
     * Test helper to verify schema loading.
     */
    public String invokeSchemaLoadingForTest(String functionName) {
        return loadSchema(functionName);
    }
}
