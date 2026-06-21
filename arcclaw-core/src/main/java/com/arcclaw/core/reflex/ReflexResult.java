package com.arcclaw.core.reflex;

import java.util.Optional;

import com.arcclaw.core.pipeline.MessageContext;

public record ReflexResult(Optional<String> commandResponse, String sanitizedInput) {
    public static ReflexResult respond(String response, MessageContext context) {
        return new ReflexResult(
                Optional.of(response),
                context.getMessage() != null ? context.getMessage().text() : "" // ← null-guard
                );
    }

    public static ReflexResult forward(MessageContext context) {
        return new ReflexResult(
                Optional.empty(),
                context.getMessage() != null ? context.getMessage().text() : "" // ← null-guard
                );
    }
}
