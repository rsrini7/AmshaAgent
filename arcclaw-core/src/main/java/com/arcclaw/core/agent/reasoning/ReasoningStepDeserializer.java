package com.arcclaw.core.agent.reasoning;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ReasoningStepDeserializer extends JsonDeserializer<ReasoningStep> {

    @Override
    public ReasoningStep deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException, JsonProcessingException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        ObjectNode node = mapper.readTree(p);

        // If the JSON explicitly defines stepType, use it
        if (node.has("stepType")) {
            String stepType = node.get("stepType").asText();
            return switch (stepType) {
                case "OBSERVATION" -> mapper.treeToValue(node, ReasoningStep.ObservationStep.class);
                case "FINISH" -> mapper.treeToValue(node, ReasoningStep.FinishStep.class);
                case "PARALLEL" -> mapper.treeToValue(node, ReasoningStep.ParallelStep.class);
                default -> mapper.treeToValue(node, ReasoningStep.ToolCallStep.class);
            };
        }

        // Backward compatibility for legacy flat records based on "action" field
        String action = node.has(ToolCallJsonKeys.ACTION)
                        && !node.get(ToolCallJsonKeys.ACTION).isNull()
                ? node.get(ToolCallJsonKeys.ACTION).asText()
                : null;

        if (ReasoningStep.FINISH.equals(action)) {
            return mapper.treeToValue(node, ReasoningStep.FinishStep.class);
        } else if (ReasoningStep.PARALLEL_SENTINEL_DUNDER.equals(action)) {
            return mapper.treeToValue(node, ReasoningStep.ParallelStep.class);
        } else if (ReasoningStep.OBSERVE.equals(action)) {
            return mapper.treeToValue(node, ReasoningStep.ObservationStep.class);
        } else {
            return mapper.treeToValue(node, ReasoningStep.ToolCallStep.class);
        }
    }
}
