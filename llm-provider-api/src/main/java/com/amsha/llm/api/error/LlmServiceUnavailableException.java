package com.amsha.llm.api.error;

public non-sealed class LlmServiceUnavailableException extends LlmProviderException {
    public LlmServiceUnavailableException(String message) {
        super(message);
    }

    public LlmServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
