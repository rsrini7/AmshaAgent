package com.arcclaw.core.agent.reasoning;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

public class ReasoningStepSerializer extends JsonSerializer<ReasoningStep> {

    @Override
    public void serialize(ReasoningStep value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();

        // 1. Write the type discriminator
        String stepType =
                switch (value) {
                    case ReasoningStep.ToolCallStep _ -> "TOOL_CALL";
                    case ReasoningStep.ObservationStep _ -> "OBSERVATION";
                    case ReasoningStep.FinishStep _ -> "FINISH";
                    case ReasoningStep.ParallelStep _ -> "PARALLEL";
                };
        gen.writeStringField("stepType", stepType);

        // 2. Write all other fields
        // Since we are using records, we can't easily use default serialization for "the rest"
        // without recursion. But we can use the provider to write the record components.

        gen.writeStringField("thought", value.thought());
        gen.writeStringField("action", value.action());

        if (value.actionInput() != null) {
            gen.writeObjectField("actionInput", value.actionInput());
        } else {
            gen.writeNullField("actionInput");
        }

        gen.writeStringField("observation", value.observation());
        gen.writeNumberField("inputTokens", value.inputTokens());
        gen.writeNumberField("outputTokens", value.outputTokens());

        if (value.success() != null) {
            gen.writeBooleanField("success", value.success());
        } else {
            gen.writeNullField("success");
        }

        gen.writeStringField("errorCode", value.errorCode());
        gen.writeStringField("traceId", value.traceId());
        gen.writeStringField("rootCauseClass", value.rootCauseClass());
        gen.writeBooleanField("isFastPath", value.isFastPath());
        gen.writeBooleanField("isTruncationRecovery", value.isTruncationRecovery());

        gen.writeEndObject();
    }
}
