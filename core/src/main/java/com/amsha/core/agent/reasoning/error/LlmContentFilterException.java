package com.amsha.core.agent.reasoning.error;

public final class LlmContentFilterException extends LlmProviderException {
    public LlmContentFilterException(String message) {
        super(message);
    }

    public LlmContentFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}
