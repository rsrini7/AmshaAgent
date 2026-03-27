package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
                    String callId = "call_" + i;
                    Map<String, Object> params = deserializeParams(sessionId, req.arguments());
                    calls.add(ParallelToolCall.pending(callId, req.name(), params));
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
        try {
            Map<String, Object> parsed = objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            log.warnf("Failed to parse tool arguments JSON for session %s: %s", sessionId, e.getMessage());
            return Map.of("__error", e.getMessage(), "parameters", argsJson);
        }
    }
}
