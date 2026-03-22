package com.arcclaw.core.reflex;

import com.arcclaw.core.pipeline.MessageContext;
import java.util.Optional;

public record ReflexResult(
    Optional<String> commandResponse,
    String sanitizedInput
) {
    public static ReflexResult respond(String response, MessageContext context) {
        return new ReflexResult(Optional.of(response), context.getMessage().text());
    }

    public static ReflexResult forward(MessageContext context) {
        return new ReflexResult(Optional.empty(), context.getMessage().text());
    }
}

