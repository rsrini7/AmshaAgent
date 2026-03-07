package com.arcclaw.core.agent.reasoning;

import java.util.Map;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Parses the ChatResponse from LangChain4j into a ReasoningStep.
 */
@ApplicationScoped
public class ReasoningStepParser {

    private static final Logger log = Logger.getLogger(ReasoningStepParser.class);

    @Inject
    ObjectMapper objectMapper;

    public ReasoningStep parse(String sessionId, String agentRole, ChatResponse response) {
        AiMessage aiMessage = response.aiMessage();
        int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        if (aiMessage.hasToolExecutionRequests()) {
            ToolExecutionRequest req = aiMessage.toolExecutionRequests().getFirst();

            log.infof("LLM is attempting to call tool: '%s' with args: %s", req.name(), req.arguments());

            Map<String, Object> actionInput = Map.of();
            try {
                if (req.arguments() != null && !req.arguments().isBlank()) {
                    actionInput = objectMapper.readValue(req.arguments(), new TypeReference<Map<String, Object>>() {
                    });
                }
            } catch (Exception e) {
                log.warn("LLM provided invalid JSON arguments: " + req.arguments());
                return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH, Map.of("error",
                        "System Error: You provided invalid JSON formatting for the tool arguments. Please ensure strict JSON compliance without trailing commas."),
                        inputTokens, outputTokens);
            }
            return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput, inputTokens, outputTokens);
        } else {
            return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH,
                    Map.of("answer", aiMessage.text() != null ? aiMessage.text() : ""),
                    inputTokens, outputTokens);
        }
    }
}
