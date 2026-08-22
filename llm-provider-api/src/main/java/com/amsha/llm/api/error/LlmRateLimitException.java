package com.amsha.llm.api.error;

public final class LlmRateLimitException extends LlmProviderException {
    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
