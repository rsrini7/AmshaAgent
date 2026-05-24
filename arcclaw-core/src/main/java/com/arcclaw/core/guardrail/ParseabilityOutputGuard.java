package com.arcclaw.core.guardrail;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Priority(100)
public class ParseabilityOutputGuard implements PostLlmGuard {

    private static final int MAX_RESPONSE_LENGTH = 100_000;

    @Override
    public GuardDecision validate(String sessionId, String userId, String userMessage, String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new GuardDecision.Retry("LLM returned empty response", null);
        }

        if (llmResponse.length() > MAX_RESPONSE_LENGTH) {
            return new GuardDecision.Sanitize(truncate(llmResponse, MAX_RESPONSE_LENGTH));
        }

        return new GuardDecision.Allow();
    }

    private static String truncate(String text, int max) {
        return text.substring(0, max) + "\n...[truncated by output guard]";
    }
}
