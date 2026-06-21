package com.amsha.core.guardrail;

public interface PostLlmGuard {

    GuardDecision validate(String sessionId, String userId, String userMessage, String llmResponse);
}
