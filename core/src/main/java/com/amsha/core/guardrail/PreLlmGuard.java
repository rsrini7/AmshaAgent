package com.amsha.core.guardrail;

public interface PreLlmGuard {

    GuardDecision validate(String sessionId, String userId, String userMessage, String assembledPrompt);
}
