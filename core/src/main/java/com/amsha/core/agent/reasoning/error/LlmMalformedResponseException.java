package com.amsha.core.agent.reasoning.error;

public final class LlmMalformedResponseException extends LlmProviderException {
    public LlmMalformedResponseException(String message) {
        super(message);
    }

    public LlmMalformedResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
