package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.scope.WorkspaceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

/**
 * Parses the ChatResponse from LangChain4j into a ReasoningStep.
 */
@ApplicationScoped
public class ReasoningStepParser {

    private static final Logger log = Logger.getLogger(ReasoningStepParser.class);

    private final ObjectMapper objectMapper;

    protected ReasoningStepParser() {
        this.objectMapper = null;
    }

    @Inject
    public ReasoningStepParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReasoningStep parse(String sessionId, String agentRole, ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();

            if (requests.size() == 1) {
                ToolExecutionRequest req = requests.getFirst();
                log.infof("LLM is attempting to call tool: '%s' with args: %s", req.name(), req.arguments());

                Map<String, Object> actionInput = deserializeParams(sessionId, req.arguments());
                if (actionInput.containsKey("__error")) {
                    return ReasoningStep.schemaError(
                            "System: Your previous response contained invalid JSON. " +
                                    "Correct the format and retry. Error: " + actionInput.get("__error"),
                            inputTokens, outputTokens
                    );
                }

                // §Bug4: Validation for blank required parameters
                if (ToolCapability.MEMORY_SEARCH.toolName().equals(req.name())) {
                    String query = (String) actionInput.get("query");
                    
                    // §Issue 7: Fallback to raw user goal if query looks garbled or empty
                    if (query == null || query.isBlank() || isUnicodeCorrupted(query)) {
                        String rawGoal = WorkspaceContext.RAW_USER_GOAL.isBound() 
                                ? WorkspaceContext.RAW_USER_GOAL.get() : null;
                        
                        if (rawGoal != null && !rawGoal.isBlank()) {
                            log.infof("Issue 7: Replacing garbled memory_search query '%s' with raw user goal", query);
                            actionInput.put("query", rawGoal);
                            query = rawGoal;
                        }
                    }

                    if (query == null || query.isBlank()) {
                        log.warnf("LLM emitted %s with blank query for session %s. Forcing correction.", req.name(), sessionId);
                        return ReasoningStep.schemaError(
                            "System: The 'query' parameter for '" + req.name() + "' cannot be blank. " +
                            "Please provide factual keywords for the search.",
                            inputTokens, outputTokens
                        );
                    }
                }

                return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput, inputTokens, outputTokens);
            }

            if (requests.size() > 1) {
                log.infof("[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel " +
                          "tool calls: %s — routing to ParallelToolStepExecutor.",
                          sessionId, agentRole, requests.size(),
                          requests.stream()
                              .map(ToolExecutionRequest::name)
                              .collect(Collectors.joining(", ")));

                List<ParallelToolCall> calls = new ArrayList<>();
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest req = requests.get(i);
                    // §7.19: callId must be non-null for merge loop correctness.
                    // Ollama does not provide tool call IDs — generate stable fallback.
                    String callId = req.id() != null
                        ? req.id()
                        : req.name() + "-" + UUID.randomUUID();
                    Map<String, Object> params = deserializeParams(sessionId, req.arguments());
                    String paramsJson = "{}";
                    try {
                        paramsJson = objectMapper.writeValueAsString(params);
                    } catch (Exception e) {
                        log.warnf("Failed to serialize params for tool %s: %s", req.name(), e.getMessage());
                    }
                    calls.add(ParallelToolCall.pending(callId, req.name(), params, paramsJson));
                }

                Map<String, Object> batchPayload;
                try {
                    String batchJson = objectMapper.writeValueAsString(calls);
                    batchPayload = Map.of("batch", batchJson);
                } catch (Exception e) {
                    log.errorf("Failed to serialize parallel batch for session %s: %s",
                               sessionId, e.getMessage());
                    log.warnf("[PARALLEL-TOOLS] Serialization failure — " +
                              "falling back to first tool '%s' only.", requests.getFirst().name());
                    ToolExecutionRequest req = requests.getFirst();
                    Map<String, Object> actionInput = deserializeParams(sessionId, req.arguments());
                    return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput, inputTokens, outputTokens);
                }

                return ReasoningStep.parallelBatch(
                    aiMessage.text(),
                    batchPayload,
                    inputTokens,
                    outputTokens
                );
            }
        }
        
        return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH,
                Map.of("answer", aiMessage.text() != null ? aiMessage.text() : ""),
                inputTokens, outputTokens);
    }

    private Map<String, Object> deserializeParams(String sessionId, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        
        // Phase 1: Try direct parse
        try {
            return doDeserialize(argsJson);
        } catch (Exception e) {
            log.warnf("Initial JSON parse failed for session %s: %s. Attempting repair.", sessionId, e.getMessage());
            
            // Phase 2: Repair and retry
            try {
                String repaired = sanitizeJson(argsJson);
                return doDeserialize(repaired);
            } catch (Exception e2) {
                log.errorf("JSON repair failed for session %s: %s", sessionId, e2.getMessage());
                return Map.of("__error", e.getMessage(), "parameters", argsJson);
            }
        }
    }

    private Map<String, Object> doDeserialize(String json) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        if (parsed != null) {
            parsed.entrySet().forEach(entry -> {
                if (entry.getValue() instanceof String s) {
                    if ("true".equalsIgnoreCase(s)) {
                        entry.setValue(Boolean.TRUE);
                    } else if ("false".equalsIgnoreCase(s)) {
                        entry.setValue(Boolean.FALSE);
                    }
                }
            });
        }
        return parsed != null ? parsed : Map.of();
    }

    /**
     * Attempts to repair common LLM tool-calling JSON errors:
     * 1. Unescaped newlines within string values
     * 2. Unescaped double-quotes within what looks like a code field
     */
    private String sanitizeJson(String json) {
        if (json == null) return null;
        
        // Replace literal newlines with \n ONLY if they are not escaped
        // This is a naive heuristic but works for code blocks.
        // We look for a newline that is preceded by something other than \
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        char prev = 0;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            
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

    private boolean isUnicodeCorrupted(String text) {
        if (text == null || text.isBlank()) return false;
        // Count Unicode replacement characters or private-use area characters (Issue 7)
        long badChars = text.chars()
                .filter(c -> c == 0xFFFD || (c >= 0xE000 && c <= 0xF8FF))
                .count();
        return badChars > 0;
    }
}
