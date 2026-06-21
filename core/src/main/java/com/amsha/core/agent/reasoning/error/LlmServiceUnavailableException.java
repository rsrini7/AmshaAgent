package com.amsha.core.agent.reasoning.error;

public final class LlmServiceUnavailableException extends LlmProviderException {
    public LlmServiceUnavailableException(String message) {
        super(message);
    }

    public LlmServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
