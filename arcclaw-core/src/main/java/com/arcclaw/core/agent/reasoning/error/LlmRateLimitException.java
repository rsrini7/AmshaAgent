package com.arcclaw.core.agent.reasoning.error;

public final class LlmRateLimitException extends LlmProviderException {
    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
