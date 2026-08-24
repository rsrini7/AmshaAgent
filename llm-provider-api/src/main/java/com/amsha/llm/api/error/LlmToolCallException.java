package com.amsha.llm.api.error;

public final class LlmToolCallException extends LlmProviderException {
    public LlmToolCallException(String message) {
        super(message);
    }

    public LlmToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
